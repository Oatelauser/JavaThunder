package io.github.oatelauser.thunder.core.internal.bencode;

/** Bencode 四种类型的封闭总和类型：整数、字节串、列表、字典。 */
public sealed interface BencodeValue permits BInteger, BString, BList, BDict {
}
