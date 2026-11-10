package share.relay;

import java.io.IOException;

/**
 * 一个模组对应一个通道。新增模组时实现本接口并在 {@link RelayServer#CHANNELS} 注册，不要另起 jar/端口。
 * 连接完成 HELLO 握手后交给对应通道，handle 在该连接自己的线程里运行，返回(或抛异常)即断开。
 */
public interface Channel {
    /** 与客户端 HELLO 里的 channel 一致，约定用模组 id。 */
    String id();

    /** 通道支持的协议版本，HELLO 版本不同则拒绝。 */
    int version();

    void handle(Connection conn) throws IOException;

    /** 进程退出/重启前把还没落盘的数据写到磁盘。 */
    default void flush() { }
}
