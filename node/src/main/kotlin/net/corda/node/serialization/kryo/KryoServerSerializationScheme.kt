package net.corda.node.serialization.kryo

//TODO: get rid of this entirely (but not yet, as it lives in the public API)
@Deprecated("Use KryoCheckpointSerializer instead")
class KryoServerSerializationScheme : AbstractKryoCheckpointSerializer()

object KryoCheckpointSerializer : AbstractKryoCheckpointSerializer()
