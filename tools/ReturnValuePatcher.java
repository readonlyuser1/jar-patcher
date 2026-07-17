import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Flips one specific ICONST_0/ICONST_1 + IRETURN pair (a literal
 * "return false;"/"return true;") inside a named method, picked by its
 * 0-based occurrence position among such pairs in that method - not by
 * content, since a boolean literal carries no string to match on.
 *
 * Usage: ReturnValuePatcher <class-file> <method-name> <method-desc>
 *        <occurrence-index> <from-const 0|1> <to-const 0|1>
 */
public class ReturnValuePatcher {
    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("Usage: ReturnValuePatcher <class-file> <method-name> <method-desc> "
                    + "<occurrence-index> <from-const 0|1> <to-const 0|1>");
            System.exit(2);
        }
        Path classFile = Path.of(args[0]);
        String methodName = args[1];
        String methodDesc = args[2];
        int occurrenceIndex = Integer.parseInt(args[3]);
        int fromConst = Integer.parseInt(args[4]);
        int toConst = Integer.parseInt(args[5]);

        ClassReader reader = new ClassReader(Files.readAllBytes(classFile));
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        MethodNode target = null;
        for (MethodNode m : classNode.methods) {
            if (m.name.equals(methodName) && m.desc.equals(methodDesc)) {
                target = m;
                break;
            }
        }
        if (target == null) {
            System.err.println("ERROR: method " + methodName + methodDesc + " not found in " + classFile);
            System.exit(1);
        }

        int fromOpcode = fromConst == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1;
        int toOpcode = toConst == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1;

        AbstractInsnNode[] instructions = target.instructions.toArray();
        int seen = 0;
        AbstractInsnNode matchToPatch = null;
        for (int i = 0; i < instructions.length; i++) {
            AbstractInsnNode insn = instructions[i];
            if (insn.getOpcode() != fromOpcode) continue;

            int j = i + 1;
            while (j < instructions.length
                    && (instructions[j] instanceof LabelNode
                        || instructions[j] instanceof LineNumberNode
                        || instructions[j] instanceof FrameNode)) {
                j++;
            }
            if (j >= instructions.length || instructions[j].getOpcode() != Opcodes.IRETURN) continue;

            if (seen == occurrenceIndex) {
                matchToPatch = insn;
                break;
            }
            seen++;
        }

        if (matchToPatch == null) {
            System.err.println("ERROR: occurrence " + occurrenceIndex + " of a literal-" + fromConst
                    + "-return not found in " + methodName + methodDesc + " (found " + seen + " total) - has the code changed?");
            System.exit(1);
        }

        target.instructions.set(matchToPatch, new InsnNode(toOpcode));
        System.out.println("Patched occurrence " + occurrenceIndex + " of return " + (fromConst != 0) + " -> "
                + (toConst != 0) + " in " + methodName + methodDesc);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        Files.write(classFile, writer.toByteArray());
        System.out.println("Wrote " + classFile);
    }
}
