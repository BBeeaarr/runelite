package net.runelite.client.plugins.worldhopper.ping;

import com.sun.jna.Structure;

import java.util.List;

public class TcpInfoV0 extends Structure
{
    public int  State;              // TCPSTATE
    public int  Mss;                // ULONG
    public long ConnectionTimeMs;   // ULONG64

    public byte TimestampsEnabled;  // BOOLEAN (1 byte)
    public byte[] _pad1 = new byte[3]; // alignment padding

    public int  RttUs;              // ULONG
    public int  MinRttUs;           // ULONG
    public int  BytesInFlight;      // ULONG
    public int  Cwnd;               // ULONG
    public int  SndWnd;             // ULONG
    public int  RcvWnd;             // ULONG
    public int  RcvBuf;             // ULONG
    public long BytesOut;           // ULONG64
    public long BytesIn;            // ULONG64
    public int  BytesReordered;     // ULONG
    public int  BytesRetrans;       // ULONG
    public int  FastRetrans;        // ULONG
    public int  DupAcksIn;          // ULONG
    public int  TimeoutEpisodes;    // ULONG
    public byte SynRetrans;         // UCHAR

    @Override
    protected List<String> getFieldOrder()
    {
        return List.of(
                "State", "Mss", "ConnectionTimeMs",
                "TimestampsEnabled", "_pad1",
                "RttUs", "MinRttUs", "BytesInFlight", "Cwnd", "SndWnd", "RcvWnd", "RcvBuf",
                "BytesOut", "BytesIn",
                "BytesReordered", "BytesRetrans", "FastRetrans", "DupAcksIn", "TimeoutEpisodes",
                "SynRetrans"
        );
    }
}