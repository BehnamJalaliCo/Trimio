package io.trimio.engine.motion.score

/**
 * The shapes a small on-device model may emit for a score. [gbnf] and [jsonSchema] accept
 * everything [Score.parse] accepts — every field optional — and nothing it would silently drop:
 * enums pinned to the literals the compiler knows, numbers as floats, strings free of control
 * characters. [repair] then cleans the slips a chat model makes even under a grammar, so the
 * director runs on constrained decoding or on plain output.
 */
object ScoreGrammar {

    /** Constrained decoding: the only object shapes a local model may produce for a [Score]. */
    val gbnf: String = """
        root ::= ws score ws

        ws ::= [ \t\n\r]*

        score ::= "{" ws score_pairs? ws "}"
        score_pairs ::= score_pair ("," ws score_pair)*
        score_pair ::=
            "\"format\"" ws ":" ws format
          | "\"look\"" ws ":" ws look
          | "\"accent\"" ws ":" ws string
          | "\"background\"" ws ":" ws background
          | "\"lens\"" ws ":" ws lens
          | "\"bpm\"" ws ":" ws number
          | "\"beatOffset\"" ws ":" ws number
          | "\"captions\"" ws ":" ws captions
          | "\"scenes\"" ws ":" ws scenes
          | "\"auto\"" ws ":" ws boolean
          | "\"variety\"" ws ":" ws number

        captions ::= "{" ws captions_pairs? ws "}"
        captions_pairs ::= captions_pair ("," ws captions_pair)*
        captions_pair ::=
            "\"show\"" ws ":" ws boolean
          | "\"recipe\"" ws ":" ws string
          | "\"place\"" ws ":" ws caption_place
          | "\"maxWords\"" ws ":" ws number
          | "\"mark\"" ws ":" ws caption_mark

        scenes ::= "[" ws (scene ("," ws scene)*)? ws "]"
        scene ::= "{" ws scene_pairs? ws "}"
        scene_pairs ::= scene_pair ("," ws scene_pair)*
        scene_pair ::=
            "\"from\"" ws ":" ws number
          | "\"time\"" ws ":" ws number
          | "\"bg\"" ws ":" ws bg
          | "\"camera\"" ws ":" ws camera
          | "\"transition\"" ws ":" ws transition
          | "\"beats\"" ws ":" ws beats
          | "\"inset\"" ws ":" ws inset

        beats ::= "[" ws (beat ("," ws beat)*)? ws "]"
        beat ::= "{" ws beat_pairs? ws "}"
        beat_pairs ::= beat_pair ("," ws beat_pair)*
        beat_pair ::=
            "\"recipe\"" ws ":" ws string
          | "\"at\"" ws ":" ws number
          | "\"until\"" ws ":" ws number
          | "\"time\"" ws ":" ws number
          | "\"hold\"" ws ":" ws number
          | "\"text\"" ws ":" ws string
          | "\"emphasis\"" ws ":" ws strings
          | "\"place\"" ws ":" ws beat_place
          | "\"energy\"" ws ":" ws number
          | "\"value\"" ws ":" ws number
          | "\"from\"" ws ":" ws number
          | "\"prefix\"" ws ":" ws string
          | "\"suffix\"" ws ":" ws string
          | "\"decimals\"" ws ":" ws number
          | "\"points\"" ws ":" ws numbers
          | "\"icon\"" ws ":" ws string
          | "\"label\"" ws ":" ws string
          | "\"mark\"" ws ":" ws beat_mark
          | "\"items\"" ws ":" ws strings
          | "\"visual\"" ws ":" ws string
          | "\"visuals\"" ws ":" ws strings
          | "\"media\"" ws ":" ws string
          | "\"aspect\"" ws ":" ws number
          | "\"brand\"" ws ":" ws string
          | "\"variant\"" ws ":" ws number
          | "\"itemAt\"" ws ":" ws numbers
          | "\"accent\"" ws ":" ws string

        strings ::= "[" ws (string ("," ws string)*)? ws "]"
        numbers ::= "[" ws (number ("," ws number)*)? ws "]"

        format ::= "\"9:16\"" | "\"1:1\"" | "\"4:5\"" | "\"16:9\""
        look ::= "\"noir\"" | "\"paper\"" | "\"lumen\""
        background ::= "\"glow\"" | "\"leaks\"" | "\"bokeh\"" | "\"dust\"" | "\"liquid\""
        lens ::= "\"halation\"" | "\"lens\"" | "\"chromatic\""
        camera ::= "\"push-in\"" | "\"pull-out\"" | "\"drift\"" | "\"still\""
        transition ::= "\"cut\"" | "\"whip\"" | "\"zoom\"" | "\"flash\"" | "\"leak\""
        bg ::= "\"media\"" | "\"aurora\"" | "\"grid\"" | "\"plain\""
        inset ::= "\"corner\"" | "\"top\""
        caption_place ::= "\"lower\"" | "\"center\"" | "\"top\""
        beat_place ::= "\"top\"" | "\"center\"" | "\"lower\"" | "\"full\""
        caption_mark ::= "\"block\"" | "\"ink\"" | "\"underline\"" | "\"circle\"" | "\"scribble\"" | "\"marker\""
        beat_mark ::= "\"block\"" | "\"ink\"" | "\"underline\"" | "\"circle\""

        boolean ::= "\"true\"" | "\"false\""

        string ::= "\"" char* "\""
        char ::= [^"\\\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [a-fA-F0-9] [a-fA-F0-9] [a-fA-F0-9] [a-fA-F0-9])

        number ::= ("-"? ([0-9] | [1-9] [0-9]*)) ("." [0-9]+)? ([eE] [-+]? [0-9]+)?
    """.trimIndent()

    /** The same contract as a draft-07 JSON Schema, for servers that validate instead of decoding. */
    val jsonSchema: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "Score",
          "description": "A Trimio motion score; every field is optional.",
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "format": { "type": "string", "enum": ["9:16", "1:1", "4:5", "16:9"] },
            "look": { "type": "string", "enum": ["noir", "paper", "lumen"] },
            "accent": { "type": "string" },
            "background": { "type": "string", "enum": ["glow", "leaks", "bokeh", "dust", "liquid"] },
            "lens": { "type": "string", "enum": ["halation", "lens", "chromatic"] },
            "bpm": { "type": "number" },
            "beatOffset": { "type": "number" },
            "captions": { "${'$'}ref": "#/definitions/captionScore" },
            "scenes": { "type": "array", "items": { "${'$'}ref": "#/definitions/sceneScore" } },
            "auto": { "type": "boolean" },
            "variety": { "type": "integer" }
          },
          "definitions": {
            "captionScore": {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "show": { "type": "boolean" },
                "recipe": { "type": "string" },
                "place": { "type": "string", "enum": ["lower", "center", "top"] },
                "maxWords": { "type": "integer" },
                "mark": { "type": "string", "enum": ["block", "ink", "underline", "circle", "scribble", "marker"] }
              }
            },
            "sceneScore": {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "from": { "type": "integer" },
                "time": { "type": "number" },
                "bg": { "type": "string", "enum": ["media", "aurora", "grid", "plain"] },
                "camera": { "type": "string", "enum": ["push-in", "pull-out", "drift", "still"] },
                "transition": { "type": "string", "enum": ["cut", "whip", "zoom", "flash", "leak"] },
                "beats": { "type": "array", "items": { "${'$'}ref": "#/definitions/beatScore" } },
                "inset": { "type": "string", "enum": ["corner", "top"] }
              }
            },
            "beatScore": {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "recipe": { "type": "string" },
                "at": { "type": "integer" },
                "until": { "type": "integer" },
                "time": { "type": "number" },
                "hold": { "type": "number" },
                "text": { "type": "string" },
                "emphasis": { "type": "array", "items": { "type": "string" } },
                "place": { "type": "string", "enum": ["top", "center", "lower", "full"] },
                "energy": { "type": "number" },
                "value": { "type": "number" },
                "from": { "type": "number" },
                "prefix": { "type": "string" },
                "suffix": { "type": "string" },
                "decimals": { "type": "integer" },
                "points": { "type": "array", "items": { "type": "number" } },
                "icon": { "type": "string" },
                "label": { "type": "string" },
                "mark": { "type": "string", "enum": ["block", "ink", "underline", "circle"] },
                "items": { "type": "array", "items": { "type": "string" } },
                "visual": { "type": "string" },
                "visuals": { "type": "array", "items": { "type": "string" } },
                "media": { "type": "string" },
                "aspect": { "type": "number" },
                "brand": { "type": "string" },
                "variant": { "type": "integer" },
                "itemAt": { "type": "array", "items": { "type": "integer" } },
                "accent": { "type": "string" }
              }
            }
          }
        }
    """.trimIndent()

    /**
     * Cleans model output down to the JSON object [Score.parse] reads: code fences, the prose
     * around the object, curly quotes and Persian digits JSON cannot parse, commas left before a
     * closer. Quote and comma fixes only fire outside a string — curly quotes inside one are
     * speech and a comma there is content — so the object survives whole; Persian digits go Latin
     * across the object so numeric fields always read as numbers.
     */
    fun repair(raw: String): String {
        val text = stripFences(raw)
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) fixJson(text.substring(start, end + 1)) else text.trim()
    }

    /** A fence is markdown chrome; its language tag and newline leave with the backticks. */
    private fun stripFences(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            if (raw.startsWith("```", i)) {
                i += 3
                while (i < raw.length && raw[i].isLetterOrDigit()) i++
                if (i < raw.length && raw[i] == '\r') i++
                if (i < raw.length && raw[i] == '\n') i++
            } else {
                out.append(raw[i])
                i++
            }
        }
        return out.toString()
    }

    /**
     * One scanner does the character fixes so string boundaries stay known: a curly double quote
     * only opens or closes when one is not already inside a string, so «say “hi”» in a text field
     * keeps its quotes while a curly-quoted key becomes a real one.
     */
    private fun fixJson(json: String): String {
        val out = StringBuilder(json.length)
        var i = 0
        var inString = false
        var curlyDelim = false
        while (i < json.length) {
            val c = json[i]
            if (inString) {
                when {
                    c == '\\' && i + 1 < json.length -> {
                        out.append(c).append(json[i + 1])
                        i += 2
                    }
                    (c == '"' && !curlyDelim) || (isCurlyDouble(c) && curlyDelim) -> {
                        inString = false
                        out.append('"')
                        i++
                    }
                    c == '\u2018' || c == '\u2019' -> {
                        out.append('\'')
                        i++
                    }
                    isPersianDigit(c) -> {
                        out.append(latinDigit(c))
                        i++
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            } else {
                when {
                    c == '"' || isCurlyDouble(c) -> {
                        inString = true
                        curlyDelim = c != '"'
                        out.append('"')
                        i++
                    }
                    c == '\u2018' || c == '\u2019' -> {
                        out.append('\'')
                        i++
                    }
                    c == ',' && isTrailingComma(json, i) -> i++
                    isPersianDigit(c) -> {
                        out.append(latinDigit(c))
                        i++
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
        }
        return out.toString()
    }

    /** A comma that only waits for the next closer is a slip, not a separator. */
    private fun isTrailingComma(json: String, commaIndex: Int): Boolean {
        var j = commaIndex + 1
        while (j < json.length && isJsonWs(json[j])) j++
        return j < json.length && (json[j] == '}' || json[j] == ']')
    }

    private fun isJsonWs(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private fun isCurlyDouble(c: Char): Boolean = c == '\u201C' || c == '\u201D' || c == '\u201E'

    private fun isPersianDigit(c: Char): Boolean = c in '\u06F0'..'\u06F9'

    private fun latinDigit(c: Char): Char = '0' + (c - '\u06F0')
}
