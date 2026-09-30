import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.baksmali.formatter.BaksmaliWriter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 dex 里抽出指定类的 smali。
 *
 *   java DumpSmali <dex 或 apk> <类名子串> [更多子串...]
 *
 * 比 baksmali 整体反汇编快得多（只写命中的类）。
 */
public final class DumpSmali {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: DumpSmali <dex/apk> <类名子串> [子串...]");
            System.exit(2);
        }
        List<String> needles = new ArrayList<String>();
        for (int i = 1; i < args.length; i++) needles.add(args[i]);

        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.getDefault());
        Writer raw = new BufferedWriter(new OutputStreamWriter(System.out, "UTF-8"));
        BaksmaliWriter out = new BaksmaliWriter(raw);

        int found = 0;
        for (ClassDef cd : dex.getClasses()) {
            String type = cd.getType();
            boolean hit = false;
            for (String n : needles) {
                if (type.contains(n)) { hit = true; break; }
            }
            if (!hit) continue;
            found++;
            new ClassDefinition(new BaksmaliOptions(), cd).writeTo(out);
        }
        out.flush();
        System.err.println("命中 " + found + " 个类");
    }
}
