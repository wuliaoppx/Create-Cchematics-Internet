package dev.cmsbp.net;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 网络线程：单线程 daemon，所有请求串行（站点没有限速，我们自己限一点）。 */
public final class Net {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cms-blueprints-net");
        t.setDaemon(true);
        return t;
    });

    private static volatile CmsClient client;

    private Net() {
    }

    public static CmsClient client() {
        CmsClient c = client;
        if (c == null) {
            synchronized (Net.class) {
                c = client;
                if (c == null) {
                    c = new CmsClient();
                    client = c;
                }
            }
        }
        return c;
    }

    public static void run(Runnable task) {
        EXEC.execute(task);
    }
}
