package com.tvlive.tv.data.epg

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * EpgIndex 二进制序列化器。
 *
 * 背景：XMLTV 源动辄 7 天、几十 MB，但 XmltvParser 只保留时间窗内的节目。
 * 把全量 XML 落盘、每次冷启动再解析一遍是主要耗时来源。这里改为：
 *   - 下载时从网络流直接解析成 EpgIndex（窗口内数据），不落全量 XML；
 *   - 缓存只写序列化后的精简索引（通常几百 KB），命中时直接读回，连 XML 解析都省掉。
 *
 * 格式带 magic + version，旧版 XML 缓存会被识别为无效并触发重新下载。
 * 字符串用「长度前缀 + UTF-8」而非 DataOutputStream.writeUTF，避免 64 KB 上限
 * （部分节目简介会超过该长度）。
 */
object EpgIndexSerializer {

    private const val MAGIC = 0x54564C45 // 'TVLE'
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 1_000_000 // 防御损坏缓存导致的超大分配

    fun write(index: EpgIndex, output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        out.writeInt(VERSION)

        val aliases = index.aliasMapForSerialization()
        out.writeInt(aliases.size)
        for ((key, value) in aliases) {
            writeString(out, key)
            writeString(out, value)
        }

        val programs = index.programsForSerialization()
        out.writeInt(programs.size)
        for ((channelId, list) in programs) {
            writeString(out, channelId)
            out.writeInt(list.size)
            for (p in list) {
                writeString(out, p.title)
                out.writeLong(p.startMs)
                out.writeLong(p.stopMs)
                val desc = p.description
                if (desc == null) {
                    out.writeBoolean(false)
                } else {
                    out.writeBoolean(true)
                    writeString(out, desc)
                }
            }
        }
        out.flush()
    }

    /** 读取失败/格式不符时返回 null，调用方据此触发重新下载。 */
    fun read(input: InputStream): EpgIndex? {
        return try {
        val dis = DataInputStream(input)
        if (dis.readInt() != MAGIC) return null
        if (dis.readInt() != VERSION) return null

        val aliasCount = dis.readInt()
        if (aliasCount < 0 || aliasCount > 200_000) return null
        val aliases = HashMap<String, String>(aliasCount.coerceAtMost(4096))
        repeat(aliasCount) {
            val key = readString(dis) ?: return null
            val value = readString(dis) ?: return null
            aliases[key] = value
        }

        val channelCount = dis.readInt()
        if (channelCount < 0 || channelCount > 100_000) return null
        val programs = HashMap<String, MutableList<EpgProgram>>(channelCount.coerceAtMost(4096))
        repeat(channelCount) {
            val channelId = readString(dis) ?: return null
            val progCount = dis.readInt()
            if (progCount < 0 || progCount > 200_000) return null
            val list = ArrayList<EpgProgram>(progCount.coerceAtMost(1024))
            repeat(progCount) {
                val title = readString(dis) ?: return null
                val startMs = dis.readLong()
                val stopMs = dis.readLong()
                val hasDesc = dis.readBoolean()
                val desc = if (hasDesc) readString(dis) ?: return null else null
                list += EpgProgram(channelId, title, startMs, stopMs, desc)
            }
            programs[channelId] = list
        }
        EpgIndex(programs, aliases)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String? {
        val n = input.readInt()
        if (n < 0 || n > MAX_STRING_BYTES) return null
        val bytes = ByteArray(n)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
