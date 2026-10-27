import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Remaps a Minecraft obfuscated jar into the MCP/SRG namespace used at runtime by
 * Forge 1.12.2 (class names = MCP paths, member names = SRG func_/field_ names).
 *
 * usage: TsrgRemap <joined.tsrg> <in.jar> <out.jar>
 */
public class TsrgRemap {

    static final Map<String, String> classMap = new HashMap<>();
    static final Map<String, Map<String, String>> fieldMap = new HashMap<>();
    static final Map<String, Map<String, String>> methodMap = new HashMap<>();

    static void loadTsrg(Path p) throws IOException {
        String cur = null;
        for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (raw.isEmpty() || raw.startsWith("#")) continue;
            if (raw.charAt(0) != '\t' && raw.charAt(0) != ' ') {
                String[] s = raw.split(" ");
                if (s.length == 1) { // package mapping line (tsrg v2)
                    classMap.put(s[0] + "/", s[0] + "/");
                    cur = null;
                    continue;
                }
                classMap.put(s[0], s[1]);
                cur = s[0]; // member maps are keyed by the OBFUSCATED owner name
                continue;
            }
            if (cur == null) continue;
            String line = raw.trim();
            String[] s = line.split(" ");
            if (s.length == 2) {
                String obf = s[0], srg = s[1];
                // fields may be listed as "a field_1" ; skip static-init / synthetic artifacts
                fieldMap.computeIfAbsent(cur, k -> new HashMap<>()).put(obf, srg);
            } else if (s.length == 3) {
                String obf = s[0], desc = s[1], srg = s[2];
                methodMap.computeIfAbsent(cur, k -> new HashMap<>()).put(obf + desc, srg);
            }
        }
    }

    static final class R extends Remapper {
        @Override
        public String map(String internalName) {
            String m = classMap.get(internalName);
            return m != null ? m : internalName;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            Map<String, String> m = fieldMap.get(owner);
            if (m != null) {
                String n = m.get(name);
                if (n != null) return n;
            }
            return name;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            Map<String, String> m = methodMap.get(owner);
            if (m != null) {
                String n = m.get(name + descriptor);
                if (n != null) return n;
            }
            return name;
        }
    }

    public static void main(String[] args) throws Exception {
        loadTsrg(Paths.get(args[0]));
        int mapped = 0, copied = 0, skipped = 0;
        Remapper remapper = new R();
        ClassWriter cw0 = new ClassWriter(0);

        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(Files.newInputStream(Paths.get(args[1]))));
             ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(args[2]))))) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (name.startsWith("META-INF/")) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".sf") || lower.endsWith(".rsa") || lower.endsWith(".dsa")) { skipped++; continue; }
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int r;
                while ((r = zin.read(buf)) > 0) bo.write(buf, 0, r);
                byte[] data = bo.toByteArray();

                if (!name.endsWith(".class")) {
                    ZipEntry ne = new ZipEntry(name);
                    zout.putNextEntry(ne);
                    zout.write(data);
                    zout.closeEntry();
                    copied++;
                    continue;
                }

                String clsName = name.substring(0, name.length() - 6);
                try {
                    ClassReader cr = new ClassReader(data);
                    ClassWriter cw = new ClassWriter(0);
                    ClassVisitor cv = new ClassRemapper(cw, remapper);
                    cr.accept(cv, 0);
                    ZipEntry ne = new ZipEntry(remapper.map(clsName) + ".class");
                    zout.putNextEntry(ne);
                    zout.write(cw.toByteArray());
                    zout.closeEntry();
                    mapped++;
                } catch (Throwable t) {
                    System.err.println("!! failed on " + name + " : " + t);
                    ZipEntry ne = new ZipEntry(name);
                    zout.putNextEntry(ne);
                    zout.write(data);
                    zout.closeEntry();
                    skipped++;
                }
            }
        }
        System.out.println("mapped=" + mapped + " resources=" + copied + " skipped=" + skipped);
    }
}
