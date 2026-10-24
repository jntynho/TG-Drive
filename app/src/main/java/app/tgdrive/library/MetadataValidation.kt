package app.tgdrive.library

/** Validate remote/imported identifiers and bodies before they reach paths, ZIP entries or UI. */
fun validateOperation(op:Envelope,plaintext:String?=if(op.vaultId.isEmpty())op.body else null) {
    fun id(value:String,optional:Boolean=false){require((optional&&value.isEmpty())||value.matches(Regex("[A-Za-z0-9_-]{1,120}"))){"Invalid metadata identifier"}}
    fun hash(value:String){require(value.isEmpty()||value.matches(Regex("[a-f0-9]{64}"))){"Invalid content checksum"}}
    fun part(value:Part,thumbnail:Boolean=false){
        require(value.messageId!=0L&&value.index in (if(thumbnail)-1..-1 else 0..127)&&value.plainSize>=0&&value.storedSize>=0&&value.storedSize<=8_589_934_592L&&value.plainSize<=8_589_934_592L){"Invalid content part"}
        hash(value.storedSha256);require(value.encrypted==op.vaultId.isNotEmpty()){"Content encryption differs from its metadata vault"}
    }
    require(op.schema==2&&op.body.length<=1_048_576&&op.resolves.size<=256){"Unsupported metadata operation"}
    id(op.operationId);id(op.entityId);id(op.parentOperationId,true);id(op.vaultId,true)
    if(plaintext==null)return
    when(op.kind){
        EntityKind.ITEM->plaintext.model<Item>().also{item->
            require(item.id==op.entityId&&item.size>=0);id(item.versionId,true);id(item.folderId,true);cleanName(item.name);hash(item.sha256)
            require(item.tags.size<=16&&item.tags.all{it.length<=32&&!it.any(Char::isISOControl)}&&item.mime.length<=200){"Invalid file metadata"}
        }
        EntityKind.FOLDER->plaintext.model<Folder>().also{require(it.id==op.entityId);id(it.parentId,true);cleanName(it.name)}
        EntityKind.VERSION->plaintext.model<Version>().also{v->
            require(v.id==op.entityId&&v.size>=0&&v.parts.size<=128&&v.parts.map{it.index}==v.parts.indices.toList());id(v.itemId);hash(v.sha256)
            v.parts.forEach{part(it)};require(v.parts.sumOf{it.plainSize}==v.size){"Incomplete content manifest"}
            v.thumbnail?.let{part(it,true);require(it.storedSize<=524_400&&it.plainSize<=524_288)}
        }
        EntityKind.VAULT->plaintext.model<VaultDescriptor>().also{
            require(it.id==op.entityId&&op.vaultId.isEmpty()&&it.iterations in 100_000..2_000_000&&it.passwordWrap.length<=16_384&&it.recoveryWrap.length<=16_384);cleanName(it.label)
        }
    }
}
