package share.protocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 房间里的一个子任务: 管理员建立，可以对应共享投影的某个子区域，并分配给若干玩家(按 Mojang UUID)；
 * 被分配的玩家(和管理员)可以更新状态。status: 0 待办 1 进行中 2 完成。
 */
public record TaskRecord(String id, String title, String region, List<String> assignees, int status, String note, String by) {
    public static final int MAX_ASSIGNEES = 32;

    public TaskRecord withId(String newId) {
        return new TaskRecord(newId, title, region, assignees, status, note, by);
    }

    public TaskRecord withStatus(int s, String who) {
        return new TaskRecord(id, title, region, assignees, s, note, who);
    }

    public TaskRecord withBy(String who) {
        return new TaskRecord(id, title, region, assignees, status, note, who);
    }

    public Packet.Builder write(Packet.Builder b) {
        b.str(id).str(title).str(region).i32(assignees.size());
        for (String a : assignees) b.str(a);
        b.i32(status).str(note).str(by);
        return b;
    }

    public static TaskRecord read(DataInputStream r) throws IOException {
        String id = Packet.readStr(r), title = Packet.readStr(r), region = Packet.readStr(r);
        int n = r.readInt();
        if (n < 0 || n > MAX_ASSIGNEES) throw new IOException("bad assignee count " + n);
        List<String> assignees = new ArrayList<>(n);
        for (int i = 0; i < n; i++) assignees.add(Packet.readStr(r));
        int status = r.readInt();
        String note = Packet.readStr(r), by = Packet.readStr(r);
        if (title.length() > 64 || note.length() > 200 || region.length() > 128 || status < 0 || status > 2) throw new IOException("bad task");
        return new TaskRecord(id, title, region, assignees, status, note, by);
    }
}
