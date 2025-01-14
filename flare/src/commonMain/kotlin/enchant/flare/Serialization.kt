package enchant.flare

import kotlinx.datetime.Instant
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.encoding.CompositeDecoder.Companion.DECODE_DONE
import kotlinx.serialization.encoding.CompositeDecoder.Companion.UNKNOWN_NAME
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.collections.List
import kotlin.collections.Map


inline fun <reified T> Document.data(strategy: DeserializationStrategy<T> = serializer()): T {
    val document: Document = this
    val decoder = FirebaseDecoder(document)
    return decoder.decodeSerializableValue(strategy)
}

inline fun <reified T> Collection.data(strategy: DeserializationStrategy<T> = serializer()): List<T> =
    documents.map { it.data(strategy) }

suspend inline fun <reified T> FirebaseFirestore.setDocument(
    path: String, data: T, options: Merge = Merge.None,
    strategy: SerializationStrategy<T> = serializer(),
    noinline changes: (Changes.() -> Unit)? = null
) {
    val encoder = FirebaseEncoder()
    encoder.encodeSerializableValue(strategy, data)
    return setDocument(path, encoder.map!!, options, changes)
}

suspend inline fun <reified T> FirebaseFirestore.updateDocument(
    path: String, data: T, strategy: SerializationStrategy<T> = serializer(),
    noinline changes: (Changes.() -> Unit)? = null
) {
    val encoder = FirebaseEncoder()
    encoder.encodeSerializableValue(strategy, data)
    return updateDocument(path, encoder.map!!, changes)
}


suspend inline fun <reified E> FirebaseFunctions.call(
    name: String,
    data: E? = null,
    timeout: Long? = null,
    inputStrategy: SerializationStrategy<E> = serializer(),
): Any? {
    val newData: Any? = if (data != null) {
        val encoder = FirebaseEncoder()
        encoder.encodeSerializableValue(inputStrategy, data)
        encoder.map
    } else data
    return call(name, newData, timeout)
}

suspend inline fun <reified E : Any, reified T> FirebaseFunctions.call(
    name: String,
    data: E? = null,
    timeout: Long? = null,
    inputStrategy: SerializationStrategy<E> = serializer(),
    outputStrategy: DeserializationStrategy<T> = serializer()
): T? {
    val output: Any? = call(name, data, timeout, inputStrategy)
    return if (output != null) {
        val decoder = FirebaseDecoder(output as Map<String, Any>)
        decoder.decodeSerializableValue(outputStrategy)
    } else output
}

suspend inline fun <reified E : Any, reified T> FirebaseFunctions.call(
    name: String,
    data: Any? = null,
    timeout: Long? = null,
    outputStrategy: DeserializationStrategy<T> = serializer()
): T? {
    val output: Any? = call(name, data, timeout)
    return if (output != null) {
        val decoder = FirebaseDecoder(output as Map<String, Any>)
        decoder.decodeSerializableValue(outputStrategy)
    } else output
}

@OptIn(ExperimentalSerializationApi::class)
class FirebaseEncoder(
    var list: MutableList<Any>? = null,
    var map: MutableMap<String, Any>? = null,
    val kind: StructureKind = StructureKind.MAP,
    val descriptor: SerialDescriptor? = null
) : AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule

    var index = 0
    var key: String? = null

    var size: Int = 0
    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int
    ): CompositeEncoder {
        size = collectionSize
        return super.beginCollection(descriptor, collectionSize)
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        if (this.descriptor == null) {
            map = mutableMapOf()
            return FirebaseEncoder(null, map, descriptor.kind as StructureKind, descriptor)
        }
        if (this.descriptor.getElementDescriptor(index).serialName == "kotlin.ByteArray") {
            return BlobEncoder(map!!, this.descriptor.getElementName(index++), size)
        }
        val output: Any =
            if (descriptor.kind == StructureKind.LIST) mutableListOf<Any>() else mutableMapOf<String, Any>()
        if (kind == StructureKind.LIST) list!! += output
        else map!![if (key != null) key!!.also { key = null } else this.descriptor.getElementName(
            index
        )] = output

        index++
        return FirebaseEncoder(
            (if (descriptor.kind == StructureKind.LIST) output else null) as MutableList<Any>?,
            (if (descriptor.kind != StructureKind.LIST) output else null) as MutableMap<String, Any>?,
            descriptor.kind as StructureKind, descriptor
        )
    }

    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        if (value is Instant) encodeValue(toDate(value)) else
            super.encodeSerializableValue(serializer, value)
    }

    override fun encodeByte(value: Byte) = encodeValue(value.toLong())

    override fun encodeChar(value: Char) = encodeValue(value.toString())

    override fun encodeEnum(descriptor: SerialDescriptor, index: Int) = encodeValue(index.toLong())

    override fun encodeFloat(value: Float) = encodeValue(value.toDouble())

    override fun encodeInt(value: Int) = encodeValue(value.toLong())

    override fun encodeShort(value: Short) = encodeValue(value.toLong())

    override fun encodeValue(value: Any) {
        key = null
        if (kind == StructureKind.LIST) list!! += value else
            map!![descriptor!!.getElementName(index++)] = value
    }

    override fun encodeNull() {
        key = null
        index++
    }

    override fun encodeString(value: String) {
        if (descriptor!!.getElementName(index).toIntOrNull() != null) {
            key = value
            index++
        } else encodeValue(value)
    }
}

internal expect fun toBlob(array: ByteArray): Any
internal expect fun fromBlob(blob: Any): ByteArray
internal expect fun isBlob(blob: Any?): Boolean
internal expect fun toDate(instant: Instant): Any
internal expect fun fromDate(date: Any): Instant
internal expect fun isDate(date: Any?): Boolean

@OptIn(ExperimentalSerializationApi::class)
class BlobEncoder(
    val map: MutableMap<String, Any>,
    val name: String,
    size: Int
) : AbstractEncoder() {
    private val array = ByteArray(size)
    var index = 0
    override val serializersModule: SerializersModule
        get() = EmptySerializersModule

    override fun encodeByte(value: Byte) {
        array[index++] = value
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        map[name] = toBlob(array)
    }

}

//Swap decoder with object accessor
@OptIn(ExperimentalSerializationApi::class)
class BlobDecoder(
    blob: Any,
) : AbstractDecoder() {

    var index = 0
    val blob: ByteArray = fromBlob(blob)
    override val serializersModule: SerializersModule = EmptySerializersModule

    @ExperimentalSerializationApi
    override fun decodeSequentially(): Boolean = true

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = blob.size

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (index >= blob.size) DECODE_DONE else index

    override fun decodeByte(): Byte = blob[index++]
}

@OptIn(ExperimentalSerializationApi::class)
class FirebaseDecoder(
    val map: Map<String, Any>? = null,
    val list: List<Any>? = null,
    val kind: StructureKind = StructureKind.MAP,
    val descriptor: SerialDescriptor? = null
) : AbstractDecoder() {

    var index = 0
    val itr by lazy { map!!.iterator() }
    var key: String? = null

    override val serializersModule: SerializersModule = EmptySerializersModule

    @ExperimentalSerializationApi
    override fun decodeSequentially(): Boolean = true

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int =
        map?.size ?: list!!.size

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {

        if (this.descriptor?.getElementDescriptor(index)?.serialName == "kotlin.ByteArray") {
            return BlobDecoder(map!![this.descriptor.getElementName(index++)]!!)
        }
        val data: Any =
            if (kind == StructureKind.LIST) list!![index]
            else if (this.descriptor == null) map!!
            else map!![key ?: this.descriptor.getElementName(index)]!!
        index++
        return FirebaseDecoder(
            (if (descriptor.kind != StructureKind.LIST) data else null) as Map<String, Any>?,
            (if (descriptor.kind == StructureKind.LIST) data else null) as List<Any>?,
            descriptor.kind as StructureKind,
            descriptor
        )
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = when {
        index >= map?.size ?: list!!.size -> DECODE_DONE
        map?.containsKey(descriptor.getElementName(index)) == false -> UNKNOWN_NAME
        else -> index
    }

    override fun decodeByte(): Byte =
        ((map?.get(descriptor!!.getElementName(index++)) ?: list!![index++]) as Long).toByte()

    override fun decodeChar(): Char {
        val s: String =
            (map?.get(descriptor!!.getElementName(index++)) ?: list!![index++]) as String
        if (s.length > 1) error("Decoded invalid char, instead was a string with multiple characters")
        return s[0]
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = (decodeValue() as Long).toInt()

    override fun decodeFloat(): Float = (decodeValue() as Double).toFloat()

    override fun decodeInt(): Int = (decodeValue() as Long).toInt()

    override fun decodeValue(): Any {
        key = null
        return map?.get(descriptor!!.getElementName(index++)) ?: list!![index++]
    }

    override fun decodeShort(): Short = (decodeValue() as Long).toShort()

    override fun decodeString(): String {
        return if (descriptor?.getElementDescriptor(index)?.serialName == "Instant")
            fromDate(
                map?.get(this.descriptor.getElementName(index++)) ?: list!![index++]
            ).toString()
        else if (descriptor?.getElementName(index)?.toIntOrNull() != null) {
            for (entry in itr) {
                if (entry.value is Map<*, *>) {
                    key = entry.key
                    break
                }
            }
            index++
            key ?: error("Map could not be decoded")

        } else super.decodeString()
    }

    override fun decodeNotNullMark(): Boolean {
        key = null
        return map!!.containsKey(descriptor!!.getElementName(index))
    }

    override fun decodeNull(): Nothing? {
        key = null
        index++
        return super.decodeNull()
    }
}