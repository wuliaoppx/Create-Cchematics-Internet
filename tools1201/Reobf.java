import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 把用「官方名（mojmap）」编译出来的 mod 类重映射到 Forge 1.20.1 运行时命名：
 * 类名保持官方名，成员名换成 SRG（m_/f_）。
 *
 * <p>与逐条查表的 remapper 的区别：mod 自己的类不在映射表里，但它们的
 * {@code render}/{@code keyPressed} 等方法**重写**了 MC 的方法，必须沿继承链
 * 找到父类里的同签名方法再做改名，否则运行时永远不会被回调。</p>
 *
 * usage: Reobf <toSrg.tsrg> <classesDir> <auxJars(;分隔)> <out.jar>
 */
public class Reobf {

    static final Map<String, String> classMap = new HashMap<>();
    static final Map<String, Map<String, String>> fieldMap = new HashMap<>();
    static final Map<String, Map<String, String>> methodMap = new HashMap<>();

    // owner -> [super, interfaces...]
    static final Map<String, String[]> parents = new HashMap<>();
    static final Map<String, Path> ownClasses = new HashMap<>();
    static final List<ZipFile> auxJars = new ArrayList<>();

    static void loadTsrg(Path p) throws IOException {
        String cur = null;
        for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (raw.isEmpty() || raw.startsWith("#")) {
                continue;
            }
            if (raw.charAt(0) != '\t' && raw.charAt(0) != ' ') {
                String[] s = raw.split(" ");
                if (s.length == 1) {
                    classMap.put(s[0] + "/", s[0] + "/");
                    cur = null;
                    continue;
                }
                classMap.put(s[0], s[1]);
                cur = s[0];
                continue;
            }
            if (cur == null) {
                continue;
            }
            String[] s = raw.trim().split(" ");
            if (s.length == 2) {
                fieldMap.computeIfAbsent(cur, k -> new HashMap<>()).put(s[0], s[1]);
            } else if (s.length == 3) {
                methodMap.computeIfAbsent(cur, k -> new HashMap<>()).put(s[0] + s[1], s[2]);
            }
        }
    }

    static byte[] bytesOf(String name) {
        Path own = ownClasses.get(name);
        if (own != null) {
            try {
                return Files.readAllBytes(own);
            } catch (IOException e) {
                return null;
            }
        }
        String entry = name + ".class";
        for (ZipFile zf : auxJars) {
            ZipEntry e = zf.getEntry(entry);
            if (e != null) {
                try (InputStream in = zf.getInputStream(e)) {
                    return in.readAllBytes();
                } catch (IOException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    static String[] parentsOf(String name) {
        String[] p = parents.get(name);
        if (p != null) {
            return p;
        }
        byte[] data = bytesOf(name);
        if (data == null) {
            parents.put(name, new String[0]);
            return new String[0];
        }
        ClassReader cr = new ClassReader(data);
        List<String> out = new ArrayList<>();
        if (cr.getSuperName() != null) {
            out.add(cr.getSuperName());
        }
        for (String itf : cr.getInterfaces()) {
            out.add(itf);
        }
        String[] arr = out.toArray(new String[0]);
        parents.put(name, arr);
        return arr;
    }

    /** 沿继承链找这条成员在映射表里的 SRG 名；找不到返回 null。 */
    static String resolve(Map<String, Map<String, String>> map, String owner, String key) {
        Map<String, String> direct = map.get(owner);
        if (direct != null) {
            String hit = direct.get(key);
            if (hit != null) {
                return hit;
            }
        }
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String p : parentsOf(owner)) {
            queue.add(p);
        }
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (!seen.add(cur)) {
                continue;
            }
            Map<String, String> m = map.get(cur);
            if (m != null) {
                String hit = m.get(key);
                if (hit != null) {
                    return hit;
                }
            }
            for (String p : parentsOf(cur)) {
                queue.add(p);
            }
        }
        return null;
    }

    static final class R extends Remapper {
        final Map<String, String> cacheM = new HashMap<>();
        final Map<String, String> cacheF = new HashMap<>();

        @Override
        public String map(String internalName) {
            String m = classMap.get(internalName);
            return m != null ? m : internalName;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String key = owner + "|" + name;
            if (cacheF.containsKey(key)) {
                return cacheF.get(key);
            }
            String hit = resolve(fieldMap, owner, name);
            String res = hit != null ? hit : name;
            cacheF.put(key, res);
            return res;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            if (name.startsWith("<")) {
                return name;
            }
            String key = owner + "|" + name + descriptor;
            if (cacheM.containsKey(key)) {
                return cacheM.get(key);
            }
            String hit = resolve(methodMap, owner, name + descriptor);
            String res = hit != null ? hit : name;
            cacheM.put(key, res);
            return res;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: Reobf <toSrg.tsrg> <classesDir> <auxJars> <out.jar>");
            System.exit(2);
        }
        loadTsrg(Paths.get(args[0]));

        Path dir = Paths.get(args[1]);
        try (var walk = Files.walk(dir)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                ownClasses.put(rel.substring(0, rel.length() - 6), p);
            });
        }
        if (!args[2].isEmpty()) {
            for (String jar : args[2].split(";")) {
                if (!jar.isBlank()) {
                    auxJars.add(new ZipFile(jar));
                }
            }
        }

        R remapper = new R();
        int n = 0;
        Path out = Paths.get(args[3]);
        Files.createDirectories(out.getParent());
        try (ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            for (Map.Entry<String, Path> e : ownClasses.entrySet()) {
                ClassReader cr = new ClassReader(Files.readAllBytes(e.getValue()));
                ClassWriter cw = new ClassWriter(0);
                cr.accept(new ClassRemapper(cw, remapper), 0);
                zout.putNextEntry(new ZipEntry(remapper.map(e.getKey()) + ".class"));
                zout.write(cw.toByteArray());
                zout.closeEntry();
                n++;
            }
        }
        for (ZipFile zf : auxJars) {
            zf.close();
        }
        System.out.println("reobf classes=" + n + " -> " + out);
    }
}
