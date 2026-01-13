package net.runelite.client.plugins.worldhopper.ping;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import net.runelite.client.plugins.worldhopper.ping.WindowsTcpInfoProbe;

interface Ws2_32 extends Library
{
    Ws2_32 INSTANCE = Native.load("Ws2_32", Ws2_32.class);

    int WSAIoctl(
            long s,
            int dwIoControlCode,
            Pointer lpvInBuffer,
            int cbInBuffer,
            Pointer lpvOutBuffer,
            int cbOutBuffer,
            IntByReference lpcbBytesReturned,
            Pointer lpOverlapped,
            Pointer lpCompletionRoutine
    );

    int WSAGetLastError();

    int getsockopt(
            long s,
            int level,
            int optname,
            byte[] optval,
            IntByReference optlen
    );
}