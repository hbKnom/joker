package dev.ujhhgtg.wekit.utils.monet

import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.Serializable

class MonetResourceGraph(
    nodes: List<MonetResourceNode>,
    private val xmlByOwner: Map<Int, List<MonetXmlElement>> = emptyMap(),
) : Serializable {
    private val byId = nodes.associateBy(MonetResourceNode::id)
    private val byKey = nodes.associateBy(MonetResourceNode::key)
    private val outgoingById: Map<Int, Set<Int>> = byId.mapValues { (id, node) ->
        HashSet<Int>().also { references ->
            node.values.forEach { it.value.collectReferences(references) }
            xmlByOwner[id].orEmpty().forEach { it.collectReferences(references) }
        }
    }
    private val incomingById: Map<Int, Set<Int>> = HashMap<Int, MutableSet<Int>>().also { incoming ->
        outgoingById.forEach { (sourceId, targets) ->
            targets.forEach { targetId ->
                incoming.getOrPut(targetId, ::linkedSetOf).add(sourceId)
            }
        }
    }

    init {
        // 资源表可能是 base.apk + split + 用户装的 overlay 合并出来的：同一 resourceId 或
        // 同一 (type,name) 出现两次在真机上完全可能（**不同微信版本尤其如此**）。
        //
        // 旧实现在这里 `require(...)`：真机上撞到重复项就直接抛错 → 整次莫奈解析失败、
        // 一行颜色都注入不了。现在按 `associateBy` 的「后到覆盖先到」去重，只丢弃重复项，
        // 其余资源照常参与匹配；XML 宿主不在表里也只是那几棵树的引用查不到，不会连带失败。
        if (byId.size != nodes.size || byKey.size != nodes.size) {
            WeLogger.w(
                TAG,
                "宿主资源表存在重复条目（节点 ${nodes.size}，按 id 去重后 ${byId.size}，" +
                    "按 key 去重后 ${byKey.size}），已按后到覆盖先到合并",
            )
        }
        val orphanXml = xmlByOwner.keys.count { it !in byId }
        if (orphanXml > 0) {
            WeLogger.w(TAG, "有 $orphanXml 棵 XML 的宿主资源不在资源表里，已忽略")
        }
    }

    private companion object {
        const val TAG = "MonetResourceGraph"
    }

    fun node(id: Int): MonetResourceNode? = byId[id]
    fun node(key: MonetResourceKey): MonetResourceNode? = byKey[key]
    fun nodes(type: String): List<MonetResourceNode> = byId.values.filter { it.key.type == type }

    /** 全部宿主资源节点。合成资源借槽位、类型统计都要用（只按角色取子集不够）。 */
    fun allNodes(): Collection<MonetResourceNode> = byId.values
    fun xmlTrees(ownerId: Int): List<MonetXmlElement> = xmlByOwner[ownerId].orEmpty()

    fun withXmlTree(ownerId: Int, tree: MonetXmlElement): MonetResourceGraph =
        MonetResourceGraph(byId.values.toList(), xmlByOwner + (ownerId to xmlTrees(ownerId) + tree))

    fun outgoing(id: Int): Set<Int> = outgoingById[id].orEmpty()
    fun incoming(id: Int): Set<Int> = incomingById[id].orEmpty()
}

private fun MonetXmlElement.collectReferences(result: MutableSet<Int>) {
    attributes.forEach { it.value.collectReferences(result) }
    children.forEach { it.collectReferences(result) }
}

private fun MonetResourceValue.collectReferences(result: MutableSet<Int>) {
    when (this) {
        is MonetResourceValue.Reference -> result += resourceId
        is MonetResourceValue.Complex -> {
            if (parentId != 0) result += parentId
            items.forEach { it.value.collectReferences(result) }
        }
        is MonetResourceValue.File,
        is MonetResourceValue.Text,
        is MonetResourceValue.Literal,
        -> Unit
    }
}
