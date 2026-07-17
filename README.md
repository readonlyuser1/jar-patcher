# jar-patcher

CI toolbox image for pipelines that need to fetch a third-party jar, make small,
targeted bytecode changes to it, and re-publish it — without a Docker build step of
their own. Ships two small ASM-based command-line tools plus everything needed to
unpack/repack a jar and ship it somewhere (curl, ssh, jq, unzip/zip, a headless JRE).

## What's inside

Alpine base + `bash`, `curl`, `openssh-client`, `jq`, `grep`, `coreutils`, `unzip`/`zip`,
`ca-certificates`, and a headless JRE 21 to run two bytecode tools (compiled against
[ASM](https://asm.ow2.io/) in a build stage — see [`Dockerfile`](./Dockerfile)):

- [`tools/LogLevelPatcher.java`](./tools/LogLevelPatcher.java)
- [`tools/ReturnValuePatcher.java`](./tools/ReturnValuePatcher.java)

Both take a `.class` file, patch it **in place**, and exit non-zero if the expected
pattern isn't found — so a pipeline step fails loudly instead of silently shipping an
unpatched (or wrongly patched) class.

## Image

```
ghcr.io/readonlyuser1/jar-patcher:latest
```

Public package, no credentials needed to pull. Rebuilt automatically by
[`.github/workflows/build.yml`](./.github/workflows/build.yml) on every push to `main`
that touches the `Dockerfile`. Pin by digest in production pipelines, not `:latest` —
see the note at the top of `Dockerfile`.

Both tools are on the image at:

```
/opt/tools/classes           # compiled LogLevelPatcher.class, ReturnValuePatcher.class
/opt/tools/asm.jar
/opt/tools/asm-tree.jar
```

Run them with:

```
java -cp /opt/tools/classes:/opt/tools/asm.jar:/opt/tools/asm-tree.jar <ToolName> <args...>
```

---

## LogLevelPatcher

Renames a single-argument logger call — e.g. turns a `LOGGER.error(String)` call into
`LOGGER.info(String)` — identified by the **string constant logged immediately before
it**, not by class/method name or bytecode offset. That means the patch keeps working
across rebuilds of the target jar (different line numbers, different local variable
slots) as long as the log message itself doesn't change; if the message disappears,
the tool exits `1` instead of silently doing nothing.

### Usage

```
java -cp ... LogLevelPatcher <class-file> <string-contains> <from-method> <to-method>
```

| Argument | Meaning |
|---|---|
| `<class-file>` | Path to the `.class` file to patch (patched in place). |
| `<string-contains>` | Substring to match against string constants (`LDC`) in the class. The tool patches the call that immediately follows a matching constant. |
| `<from-method>` | Current logger method name to look for, e.g. `error`, `warn`. Must be a call with descriptor `(Ljava/lang/String;)V`. |
| `<to-method>` | Method name to rename the call to, e.g. `info`, `debug`. |

### Example (illustrative, not a real class)

Say a fictional plugin logs this at `error` level whenever a legacy adapter isn't
configured:

```java
LOGGER.error("Legacy sync adapter is disabled. Contact support@example-vendor.test to enable it.");
```

and you've decided this specific message is noise, not an actual error, in your
environment. After extracting the jar:

```bash
java -cp /opt/tools/classes:/opt/tools/asm.jar:/opt/tools/asm-tree.jar LogLevelPatcher \
  "extracted/com/example/widgets/LicenseGate.class" \
  "Legacy sync adapter is disabled" \
  error info
```

Output:

```
Patching com/example/vendor/log/Logger.error(Ljava/lang/String;)V in checkLegacyAdapter()V (matched string: "Legacy sync adapter is disabled")
Patched 1 call(s) in extracted/com/example/widgets/LicenseGate.class
```

Only the call immediately following that specific string constant is touched — other
`error(...)` calls elsewhere in the same class or method are left alone.

---

## ReturnValuePatcher

Flips one specific literal `return true;` / `return false;` inside a named method,
identified by **its 0-based position** among such literals in that method — a boolean
literal has no text to match against, so unlike `LogLevelPatcher` this tool locates its
target by position, not content. Fails with exit `1` if the requested occurrence
doesn't exist (fewer matching literals than expected in the method) — so a pipeline
step never silently patches the wrong thing (or nothing at all) after the target jar
changes shape upstream.

### Usage

```
java -cp ... ReturnValuePatcher <class-file> <method-name> <method-desc> <occurrence-index> <from-const 0|1> <to-const 0|1>
```

| Argument | Meaning |
|---|---|
| `<class-file>` | Path to the `.class` file to patch (patched in place). |
| `<method-name>` | Name of the method to patch, e.g. `equals`, `isEligible`. |
| `<method-desc>` | JVM method descriptor, e.g. `(Ljava/lang/Object;)Z` for `boolean equals(Object)`. Needed to disambiguate overloads. |
| `<occurrence-index>` | 0-based index: which `ICONST_0`/`ICONST_1` + `IRETURN` pair (i.e. which literal `return false;`/`return true;`) to patch, counting from the top of the method. |
| `<from-const>` | The literal to search for: `0` for `return false;`, `1` for `return true;`. |
| `<to-const>` | The literal to replace it with: `0` or `1`. |

### Example (illustrative, not a real class)

A fictional value-object's `equals()` — decompiled for reference:

```java
public boolean equals(Object obj) {
    if (this == obj) {
        return true;               // occurrence 0
    }
    if (obj == null) {
        return false;              // occurrence 1
    }
    if (this.getClass() != obj.getClass()) {
        return false;              // occurrence 2
    }
    ExampleTag other = (ExampleTag) obj;
    return this.code == other.code;  // compiles to a conditional return, not a literal - not counted
}
```

To flip only the fast-path identity check (occurrence `0`, `return true;` →
`return false;`), leaving the rest of the method untouched:

```bash
java -cp /opt/tools/classes:/opt/tools/asm.jar:/opt/tools/asm-tree.jar ReturnValuePatcher \
  "extracted/com/example/tags/ExampleTag.class" \
  "equals" "(Ljava/lang/Object;)Z" \
  0 1 0
```

Output:

```
Patched occurrence 0 of return true -> false in equals(Ljava/lang/Object;)Z
Wrote extracted/com/example/tags/ExampleTag.class
```

**Scope it narrowly.** `<occurrence-index>` picks exactly one literal in exactly one
method — prefer that over patching every `return false;`/`return true;` in a class,
especially in methods that gate licensing, authorization, or other checks you don't
actually intend to change the meaning of.
