import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.Reference;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 找出「谁调用了某个方法」。
 *
 *   java FindRefs <dex> <引用子串> [更多子串...]
 *
 * 例如： java FindRefs classes4.dex "drawable/Drawable;->setTint("
 */
public final class FindRefs {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: FindRefs <dex> <引用子串> [子串...]");
            System.exit(2);
        }
        Set<String> needles = new LinkedHashSet<String>();
        for (int i = 1; i < args.length; i++) needles.add(args[i]);

        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.getDefault());
        int hits = 0;
        for (ClassDef cd : dex.getClasses()) {
            for (Method m : cd.getMethods()) {
                MethodImplementation impl = m.getImplementation();
                if (impl == null) continue;
                boolean printed = false;
                for (Instruction insn : impl.getInstructions()) {
                    if (!(insn instanceof ReferenceInstruction)) continue;
                    Reference ref = ((ReferenceInstruction) insn).getReference();
                    if (ref == null) continue;
                    String s = ref.toString();
                    for (String n : needles) {
                        if (s.contains(n)) {
                            hits++;
                            if (!printed) {
                                System.out.println("== " + cd.getType() + "->" + m.getName()
                                        + m.getParameterTypes());
                                printed = true;
                            }
                            System.out.println("     " + s);
                            break;
                        }
                    }
                }
            }
        }
        System.err.println("引用命中 " + hits + " 处");
    }
}
