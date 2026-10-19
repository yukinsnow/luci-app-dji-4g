package local.router.sms;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** SSH bootstrap only; never send the router password to the HTTP API. */
public final class RouterLogin {
    public static final String HOST = "192.168.8.1";
    private RouterLogin() {}

    /** Reject unknown key before authentication; this probe never supplies a password. */
    public static String discoverHostKey() throws Exception {
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519");
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH");
        JSch ssh = new JSch();
        final String[] found = {null};
        ssh.setHostKeyRepository(new com.jcraft.jsch.HostKeyRepository() {
            public int check(String host, byte[] key) {
                found[0] = HOST + " ssh-ed25519 " + java.util.Base64.getEncoder().encodeToString(key) + "\n";
                return NOT_INCLUDED;
            }
            public void add(com.jcraft.jsch.HostKey k, com.jcraft.jsch.UserInfo u) {}
            public void remove(String h, String t) {}
            public void remove(String h, String t, byte[] k) {}
            public String getKnownHostsRepositoryID() { return "identity-probe"; }
            public com.jcraft.jsch.HostKey[] getHostKey() { return new com.jcraft.jsch.HostKey[0]; }
            public com.jcraft.jsch.HostKey[] getHostKey(String h, String t) { return getHostKey(); }
        });
        Session session = ssh.getSession("root", HOST, 22);
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setConfig("server_host_key", "ssh-ed25519");
        session.setConfig("PreferredAuthentications", "none");
        try { session.connect(10000); }
        catch (com.jcraft.jsch.JSchException e) { if (found[0] == null) throw e; }
        finally { session.disconnect(); }
        if (found[0] == null) throw new IOException("无法读取 SSH 公钥");
        return found[0];
    }
    public static String fingerprint(String knownHost) throws Exception {
        byte[] key = java.util.Base64.getDecoder().decode(knownHost.trim().split(" ")[2]);
        return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(key));
    }

    public static String fetchConfiguration(String host, String username, byte[] password, String knownHost) throws Exception {
        Session session = null;
        ChannelExec channel = null;
        try {
            if (!HOST.equals(host)) throw new IOException("此版本仅支持默认 LAN 地址 192.168.8.1");
            if (!"root".equals(username)) throw new IOException("当前后端配置仅允许 root 读取，请使用路由器 SSH 管理账号");
            if (password == null || password.length == 0 || password.length > 1024) throw new IOException("请输入路由器密码");
            if (knownHost == null || !knownHost.startsWith(HOST + " ssh-ed25519 ")) throw new IOException("请先核对 SSH 指纹");
            JSch ssh = new JSch();
            // Use BC explicitly: Android does not provide the JDK 15 Ed25519 APIs.
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519");
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH");
            ssh.setKnownHosts(new ByteArrayInputStream(knownHost.getBytes(StandardCharsets.US_ASCII)));
            session = ssh.getSession(username, host, 22);
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("server_host_key", "ssh-ed25519");
            session.setConfig("kex", "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,diffie-hellman-group14-sha256");
            session.setConfig("PreferredAuthentications", "password");
            session.setPassword(password);
            session.setTimeout(15000);
            session.connect(15000);
            channel = (ChannelExec)session.openChannel("exec");
            // Constant command, no user-controlled interpolation and no changes to router.
            channel.setCommand("cat /etc/router-sms.json");
            channel.setInputStream(null);
            channel.setErrStream(new ByteArrayOutputStream());
            InputStream in = channel.getInputStream();
            channel.connect(10000);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + 15000000000L;
            byte[] buffer = new byte[1024];
            while (true) {
                if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) throw new IOException("读取配置超时，请稍后重新登录");
                while (in.available() > 0) {
                    int n = in.read(buffer, 0, Math.min(buffer.length, in.available()));
                    if (n < 0) break;
                    if (out.size() + n > 16384) throw new IOException("路由器配置响应过大");
                    out.write(buffer, 0, n);
                }
                if (channel.isClosed() && in.available() == 0) break;
                Thread.sleep(20);
            }
            if (channel.getExitStatus() != 0) throw new IOException("未找到短信后端配置，请先安装路由器后端");
            return out.toString("UTF-8");
        } finally {
            if (channel != null) channel.disconnect();
            if (session != null) session.disconnect();
            if (password != null) Arrays.fill(password, (byte)0);
        }
    }
}
