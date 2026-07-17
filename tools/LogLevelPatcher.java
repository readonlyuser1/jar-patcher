import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renames a single-argument Logger call (e.g. LOGGER.error(String) ->
 * LOGGER.info(String)) in a compiled .class file, identified by the string
 * constant logged immediately before it rather than by method name/offset -
 * so the patch keeps matching after a vendor rebuilds the class with
 * different line numbers/local variable layouts.
 *
 * Usage: LogLevelPatcher <class-file> <string-contains> <from-method> <to-method>
 */
public class LogLevelPatcher {
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: LogLevelPatcher <class-file> <string-contains> <from-method> <to-method>");
            System.exit(2);
        }
        Path classFile = Path.of(args[0]);
        String needle = args[1];
        String fromName = args[2];
        String toName = args[3];

        ClassReader reader = new ClassReader(Files.readAllBytes(classFile));
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        int patched = 0;
        for (MethodNode method : classNode.methods) {
            AbstractInsnNode[] instructions = method.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                if (!(instructions[i] instanceof LdcInsnNode ldc)) continue;
                if (!(ldc.cst instanceof String s) || !s.contains(needle)) continue;

                for (int j = i + 1; j < instructions.length; j++) {
                    AbstractInsnNode next = instructions[j];
                    if (next instanceof LabelNode || next instanceof LineNumberNode || next instanceof FrameNode) {
                        continue;
                    }
                    if (next instanceof MethodInsnNode call
                            && call.name.equals(fromName)
                            && call.desc.equals("(Ljava/lang/String;)V")
                            && call.owner.contains("Logger")) {
                        System.out.println("Patching " + call.owner + "." + call.name + call.desc
                                + " in " + method.name + method.desc + " (matched string: \"" + needle + "\")");
                        call.name = toName;
                        patched++;
                    }
                    break;
                }
            }
        }

        if (patched == 0) {
            System.err.println("ERROR: no " + fromName + "(String) call found next to a string containing \""
                    + needle + "\" - has the plugin's code changed?");
            System.exit(1);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        Files.write(classFile, writer.toByteArray());
        System.out.println("Patched " + patched + " call(s) in " + classFile);
    }
}
