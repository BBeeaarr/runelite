// WindowsTcpInfoProbe.java
//
// Purpose:
//   Windows-only TCP RTT probe for an existing, connected socket using WSAIoctl(SIO_TCP_INFO).
//
// Usage (from Ping.java):
//   FileDescriptor fd = client.getSocketFD();
//   Integer rttUs = WindowsTcpInfoProbe.tryGetRttUs(fd);
//   if (rttUs != null) { int rttMs = Math.max(1, rttUs / 1000); }
//
// Notes:
//   - Requires JNA on the classpath.
//   - Works on Windows where SIO_TCP_INFO is supported (Windows 10 1703+).
//   - Uses FileDescriptor.fd (int) as the SOCKET candidate when FileDescriptor.handle is -1.
//   - Validates the candidate with getsockopt(SO_TYPE) to avoid WSAENOTSOCK loops.

package net.runelite.client.plugins.worldhopper.ping;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Slf4j
public final class WindowsTcpInfoProbe
{
    private WindowsTcpInfoProbe() {}

    // Winsock constants
    private static final int SOL_SOCKET = 0xFFFF;
    private static final int SO_TYPE    = 0x1008;     // returns SOCK_STREAM=1 for TCP sockets
    private static final int SOCK_STREAM = 1;
    private static final int SIO_TCP_INFO = 0xD8000027;

    // Reflection fields cached once
    private static final Field FD_FIELD = getFdField();
    private static final Field HANDLE_FIELD = getHandleFieldOrNull();

    /**
     * Attempts to read TCP_INFO_v0.RttUs for the socket backing the provided FileDescriptor.
     *
     * @return RTT in microseconds if available; otherwise null.
     */
    public static @Nullable TcpInfoV0 tryGetTcpInfoV0(FileDescriptor fdObj)
    {
        if (fdObj == null)
        {
            return null;
        }

        if (!isWindows())
        {
            return null; // Explicitly Windows-only
        }

        // Prefer handle if it looks valid, otherwise use fd.
        long socketCandidate = getSocketCandidate(fdObj);
        if (socketCandidate == -1)
        {
            if (log.isDebugEnabled())
            {
                dumpFileDescriptor(fdObj);
            }
            return null;
        }

        // Validate it's actually a socket (prevents 10038 surprises later).
        if (!isWinsockStreamSocket(socketCandidate))
        {
            if (log.isDebugEnabled())
            {
                dumpFileDescriptor(fdObj);
                log.debug("SO_TYPE validation failed; not a SOCK_STREAM. candidate={}", socketCandidate);
            }
            return null;
        }

        // Query TCP_INFO_v0 via WSAIoctl(SIO_TCP_INFO)
        try
        {
            // Input buffer is a DWORD selecting version: 0 -> TCP_INFO_v0
            Pointer in = new com.sun.jna.Memory(4);
            in.setInt(0, 0);

            TcpInfoV0 out = new TcpInfoV0();
            out.write(); // init backing memory

            IntByReference bytesReturned = new IntByReference(0);

            int rc = Ws2_32.INSTANCE.WSAIoctl(
                    socketCandidate,
                    SIO_TCP_INFO,
                    in, 4,
                    out.getPointer(), out.size(),
                    bytesReturned,
                    Pointer.NULL,
                    Pointer.NULL
            );

            if (rc != 0)
            {
                int err = Ws2_32.INSTANCE.WSAGetLastError();
                if (log.isDebugEnabled())
                {
                    log.debug("WSAIoctl(SIO_TCP_INFO) failed rc={} err={} bytesReturned={} candidate={}",
                            rc, err, bytesReturned.getValue(), socketCandidate);
                }
                return null;
            }

            out.read();
            return out;
        }
        catch (Throwable t)
        {
            if (log.isDebugEnabled())
            {
                log.debug("Exception during SIO_TCP_INFO probe", t);
            }
            return null;
        }
    }

    /**
     * Optional helper for debugging during development.
     */
    public static void dumpFileDescriptor(FileDescriptor fdObj)
    {
        try
        {
            int rawFd = FD_FIELD.getInt(fdObj);
            long rawHandle = HANDLE_FIELD != null ? HANDLE_FIELD.getLong(fdObj) : Long.MIN_VALUE;
            log.debug("FileDescriptor dump: fd={} handle={}", rawFd, rawHandle);
        }
        catch (Throwable t)
        {
            log.debug("Failed to dump FileDescriptor fields", t);
        }
    }

    // ----------------------------
    // Internals
    // ----------------------------

    private static long getSocketCandidate(FileDescriptor fdObj)
    {
        try
        {
            // 1) Try handle (Windows HANDLE) if present and not sentinel
            if (HANDLE_FIELD != null)
            {
                long handle = HANDLE_FIELD.getLong(fdObj);
                if (handle > 0)
                {
                    return handle;
                }
            }

            // 2) Fall back to fd as SOCKET candidate (common in some JDK socket implementations)
            int rawFd = FD_FIELD.getInt(fdObj);
            if (rawFd >= 0)
            {
                // SOCKET is UINT_PTR; treat int as unsigned
                return Integer.toUnsignedLong(rawFd);
            }

            return -1;
        }
        catch (Throwable t)
        {
            return -1;
        }
    }

    private static boolean isWinsockStreamSocket(long s)
    {
        byte[] opt = new byte[4];
        IntByReference optLen = new IntByReference(opt.length);

        int rc = Ws2_32.INSTANCE.getsockopt(s, SOL_SOCKET, SO_TYPE, opt, optLen);
        if (rc != 0)
        {
            // Most useful error here is 10038 (WSAENOTSOCK)
            return false;
        }

        int soType = ByteBuffer.wrap(opt).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return soType == SOCK_STREAM;
    }

    private static boolean isWindows()
    {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("windows");
    }

    private static Field getFdField()
    {
        try
        {
            Field f = FileDescriptor.class.getDeclaredField("fd");
            f.setAccessible(true);
            return f;
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("Could not access FileDescriptor.fd", e);
        }
    }

    private static @Nullable Field getHandleFieldOrNull()
    {
        try
        {
            Field f = FileDescriptor.class.getDeclaredField("handle");
            f.setAccessible(true);
            return f;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    // ----------------------------
    // TCP_INFO_v0 structure layout
    // ----------------------------

    /**
     * JNA mapping of TCP_INFO_v0.
     *
     * Critical detail: BOOLEAN is 1 byte; native struct is aligned to 4 bytes,
     * so we include 3 bytes padding after TimestampsEnabled.
     */

}
