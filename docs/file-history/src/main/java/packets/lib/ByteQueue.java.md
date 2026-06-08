# `src/main/java/packets/lib/ByteQueue.java`

**Java** · 261 lines · 8,576 bytes · 5 commit(s) · first 2019-12-05 · last 2021-02-01

## Purpose

A <CODE>ByteQueue</CODE> is a queue of byte values. <b>Limitations:</b> (1) The capacity of one of these queues can change after it's created, but the maximum capacity is limited by the amount of free memory on the machine. The constructor, <CODE>add</CODE>, <CODE>clone</CODE>, and <CODE>union</CODE> will result in an <CODE>OutOfMemoryError</CODE> when free memory is exhausted. (2) A queue's capacity cannot exceed the maximum integer 2,147,483,647 (<CODE>Integer.MAX_VALUE</CODE>). Any attempt to create a larger capacity results in a failure due to an arithmetic overflow. <b>Java Source Code fo

## Commit history

| Date | Commit | Author | Summary |
| --- | --- | --- | --- |
| 2021-02-01 | `c84a342` | Mirco Kroon | Allow packets to be injected by the downloader |
| 2021-02-01 | `4136b3c` | Mirco Kroon | Rename PacketBuilder, created actual PacketBuilder |
| 2019-12-05 | `b0e6764` | Unknown | Use arrayCopy instead of loop |
| 2019-12-05 | `c021463` | Unknown | Changed LinkedList to ByteQueue to reduce memory overhead |
| 2019-12-05 | `3b02ba0` | Unknown | Added ByteQueue class |

[← file-history index](../../../../../docs/file-history/README.md)
