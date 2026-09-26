package dev.ujhhgtg.wekit.utils.monet

import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.chunk.xml.ResXmlTextNode

data class MonetBinaryXml(
    val root: MonetXmlElement,
)

object MonetBinaryXmlReader {
    fun read(document: ResXmlDocument): MonetBinaryXml {
        val referenceIds = linkedSetOf<Int>()
        val root = document.elements.asSequence().firstOrNull()
            ?: error("binary XML document has no root element")
        return MonetBinaryXml(root.toMonetElement(referenceIds))
    }

    private fun ResXmlElement.toMonetElement(referenceIds: MutableSet<Int>): MonetXmlElement =
        MonetXmlElement(
            name = name,
            namespace = uri,
            attributes = attributes.asSequence().mapNotNull { attribute ->
                // 个别属性没有 valueType：旧实现 requireNotNull 抛错 → 整棵 XML 被丢掉
                // （实机日志里「XML 解析失败 4844 个」有一部分就是这么来的）。
                // 现在只跳过这一个属性。
                val valueType = attribute.valueType ?: return@mapNotNull null
                val value = if (valueType.isReference) {
                    MonetResourceValue.Reference(attribute.data, valueType.name).also {
                        referenceIds += attribute.data
                    }
                } else {
                    MonetResourceValue.Literal(
                        valueType = valueType.name,
                        data = Integer.toUnsignedLong(attribute.data),
                    )
                }
                MonetXmlAttribute(
                    namespace = attribute.uri,
                    name = attribute.name,
                    nameId = attribute.nameId.takeIf { it != 0 },
                    valueType = valueType.name,
                    value = value,
                )
            }.toList(),
            children = iterator().asSequence().mapNotNull { child ->
                when (child) {
                    is ResXmlElement -> child.toMonetElement(referenceIds)
                    is ResXmlTextNode -> null
                    // 不认识的节点类型只跳过这一个节点，不再让整棵 XML 解析失败。
                    else -> null
                }
            }.toList(),
        )
}
