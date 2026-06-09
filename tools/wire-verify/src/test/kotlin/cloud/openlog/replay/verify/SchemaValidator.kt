package cloud.openlog.replay.verify

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.io.File

/**
 * Loads the canonical `rr-mobile-schema.json` (vendored at repo `schema/`) and
 * validates emitted event JSON against it. This is the Part 5.1 gate.
 */
object SchemaValidator {

    private val mapper = ObjectMapper()

    private val schema: JsonSchema by lazy {
        val candidates = listOf(
            File("../../schema/rr-mobile-schema.json"), // when run from tools/wire-verify
            File("schema/rr-mobile-schema.json"),       // when run from repo root
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("rr-mobile-schema.json not found; looked in: ${candidates.map { it.absolutePath }}")
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
        file.inputStream().use { factory.getSchema(it) }
    }

    /** Returns the set of validation error messages ([emptySet] means valid). */
    fun validate(json: String): Set<String> {
        val node = mapper.readTree(json)
        return schema.validate(node).map { it.message }.toSet()
    }

    fun assertValid(json: String) {
        val errors = validate(json)
        check(errors.isEmpty()) { "Event failed rr-mobile-schema validation:\n$json\nErrors:\n${errors.joinToString("\n")}" }
    }
}
