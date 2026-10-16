package io.github.yomikomi.update

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** Public GitHub release feed/page fallback when the shared, unauthenticated API quota is exhausted. */
internal object GitHubReleasePage {
    private const val repository = "/younai666/Yomikomi"
    private const val atom = "http://www.w3.org/2005/Atom"

    fun latestTag(body: String): String? {
        try {
            val parser = Xml.newPullParser().apply { setInput(StringReader(body)) }
            val tags = mutableListOf<String>()
            var entryDepth = -1
            var entries = 0
            var feed = false
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.DOCDECL -> throw UpdateException("更新订阅格式无效")
                    XmlPullParser.START_TAG -> when {
                        parser.depth == 1 -> {
                            if (parser.name != "feed" || parser.namespace != atom) throw UpdateException("更新订阅格式无效")
                            feed = true
                        }
                        parser.namespace == atom && parser.name == "entry" && parser.depth == 2 -> { entryDepth = parser.depth; entries++ }
                        entryDepth > 0 && parser.depth == entryDepth + 1 && parser.namespace == atom && parser.name == "link" -> {
                            if (parser.getAttributeValue(null, "rel") != "alternate") continue
                            val href = parser.getAttributeValue(null, "href").orEmpty()
                            val prefix = "https://github.com$repository/releases/tag/"
                            if (href.startsWith(prefix)) href.removePrefix(prefix).takeIf { AppVersion.parse(it) != null }?.let(tags::add)
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.depth == entryDepth) entryDepth = -1
                }
            }
            if (!feed || entries > 0 && tags.isEmpty()) throw UpdateException("未找到可识别的发行版本")
            return tags.maxByOrNull { checkNotNull(AppVersion.parse(it)) }
        } catch (error: UpdateException) { throw error }
        catch (error: Exception) { throw UpdateException("更新订阅格式无效", error) }
    }

    fun assets(tag: String, html: String): List<UpdateAsset> {
        val prefix = "$repository/releases/download/$tag/"
        val assets = Regex("<li\\b[^>]*>([\\s\\S]*?)</li>").findAll(html).mapNotNull { row ->
            val path = Regex("<a\\b[^>]*\\bhref=\"([^\"]+)\"").find(row.value)?.groupValues?.get(1) ?: return@mapNotNull null
            if (!path.startsWith(prefix)) return@mapNotNull null
            val name = path.removePrefix(prefix)
            if (!Regex("[A-Za-z0-9._-]+\\.apk").matches(name)) return@mapNotNull null
            val hash = Regex("<clipboard-copy\\b[^>]*\\bvalue=\"sha256:([0-9a-f]{64})\"").find(row.value)?.groupValues?.get(1)
                ?: throw UpdateException("更新文件缺少校验信息")
            // Bytes are resolved via HEAD; the digest remains pinned to GitHub's release asset row.
            UpdateAsset(hash.take(15).toLong(16) + 1, name, "https://github.com$path", 0, hash)
        }.toList()
        if (assets.isEmpty()) throw UpdateException("新版本暂无适用安装包")
        return assets
    }
}
