package offline;

import client.Character;
import client.Job;

import java.util.ArrayList;
import java.util.List;

/** The Job Switch Token: which jobs a character may switch to (same family, same advancement) and the switch. */
public final class OfflineJobSwitch {
    private OfflineJobSwitch() {}

    /** 1 first job, 2 second, 3 third, 4 fourth; 0 beginner. */
    static int tier(int job) {
        int j = job % 1000;
        if (j % 100 == 0) return j == 0 ? 0 : 1;
        return j % 10 + 2;
    }

    /** Jobs this character could switch to (not its own). Explorers among explorers, Cygnus among Cygnus. */
    public static int[] choices(Character chr) {
        int job = chr.getJob().getId();
        int tier = tier(job);
        List<Integer> out = new ArrayList<>();
        if (tier == 0) return new int[0];
        int base = job / 1000 == 1 ? 1000 : job / 1000 == 0 ? 0 : -1;
        if (base < 0) return new int[0]; // Aran: one path only
        for (int cls = 1; cls <= 5; cls++) {
            int first = base + cls * 100;
            if (tier == 1) {
                add(out, first, job);
                continue;
            }
            int branches = base == 0 ? (cls == 1 || cls == 2 ? 3 : 2) : 1; // Cygnus: one branch per class
            for (int b = 1; b <= branches; b++) add(out, first + b * 10 + (tier - 2), job);
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    private static void add(List<Integer> out, int id, int current) {
        if (id != current && Job.getById(id) != null) out.add(id);
    }

    /** Switches; false if the job is not one of the choices. */
    public static boolean apply(Character chr, int jobId) {
        boolean ok = false;
        for (int j : choices(chr)) if (j == jobId) ok = true;
        if (!ok) return false;
        chr.offlineSwitchJob(Job.getById(jobId));
        return true;
    }
}
