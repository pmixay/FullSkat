package com.fullskat.watch.richtext.math

/** Command and character tables for the LaTeX subset. Pure data plus the alphabet mapping onto Unicode math ranges. */
internal object MathSymbols {
    private fun table(spec: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (entry in spec.split(' ', '\n')) {
            if (entry.isEmpty()) continue
            val i = entry.indexOf(':')
            out[entry.substring(0, i)] = entry.substring(i + 1)
        }
        return out
    }

    val greekLower = table(
        "alpha:α beta:β gamma:γ delta:δ epsilon:ϵ varepsilon:ε zeta:ζ eta:η theta:θ vartheta:ϑ iota:ι kappa:κ varkappa:ϰ " +
            "lambda:λ mu:μ nu:ν xi:ξ omicron:ο pi:π varpi:ϖ rho:ρ varrho:ϱ sigma:σ varsigma:ς tau:τ upsilon:υ " +
            "phi:ϕ varphi:φ chi:χ psi:ψ omega:ω digamma:ϝ"
    )
    val greekUpper = table(
        "Gamma:Γ Delta:Δ Theta:Θ Lambda:Λ Xi:Ξ Pi:Π Sigma:Σ Upsilon:Υ Phi:Φ Psi:Ψ Omega:Ω " +
            "varGamma:Γ varDelta:Δ varTheta:Θ varLambda:Λ varXi:Ξ varPi:Π varSigma:Σ varUpsilon:Υ varPhi:Φ varPsi:Ψ varOmega:Ω"
    )

    val binary = table(
        "pm:± mp:∓ times:× div:÷ cdot:⋅ ast:∗ star:⋆ circ:∘ bullet:∙ wedge:∧ land:∧ vee:∨ lor:∨ cap:∩ cup:∪ " +
            "oplus:⊕ ominus:⊖ otimes:⊗ oslash:⊘ odot:⊙ uplus:⊎ sqcap:⊓ sqcup:⊔ setminus:∖ smallsetminus:∖ wr:≀ " +
            "diamond:⋄ dagger:† ddagger:‡ amalg:⨿ bigtriangleup:△ bigtriangledown:▽ triangleleft:◃ triangleright:▹ " +
            "lhd:⊲ rhd:⊳ ltimes:⋉ rtimes:⋊ barwedge:⊼ veebar:⊻ boxplus:⊞ boxminus:⊟ boxtimes:⊠ boxdot:⊡ " +
            "circledast:⊛ circledcirc:⊚ circleddash:⊝ dotplus:∔ intercal:⊺ centerdot:· cdotp:⋅ Cap:⋒ Cup:⋓ " +
            "curlywedge:⋏ curlyvee:⋎ divideontimes:⋇ leftthreetimes:⋋ rightthreetimes:⋌ bmod:mod"
    )

    val relation = table(
        "leq:≤ le:≤ geq:≥ ge:≥ neq:≠ ne:≠ approx:≈ equiv:≡ sim:∼ simeq:≃ cong:≅ propto:∝ varpropto:∝ ll:≪ gg:≫ " +
            "in:∈ ni:∋ owns:∋ notin:∉ subset:⊂ supset:⊃ subseteq:⊆ supseteq:⊇ nsubseteq:⊈ nsupseteq:⊉ " +
            "subsetneq:⊊ supsetneq:⊋ sqsubset:⊏ sqsupset:⊐ sqsubseteq:⊑ sqsupseteq:⊒ parallel:∥ nparallel:∦ " +
            "perp:⊥ vdash:⊢ dashv:⊣ models:⊨ vDash:⊨ Vdash:⊩ prec:≺ succ:≻ preceq:≼ succeq:≽ doteq:≐ asymp:≍ " +
            "lesssim:≲ gtrsim:≳ mid:∣ nmid:∤ bowtie:⋈ smile:⌣ frown:⌢ triangleq:≜ coloneqq:≔ eqqcolon:≕ " +
            "lll:⋘ ggg:⋙ lessgtr:≶ gtrless:≷ leqq:≦ geqq:≧ leqslant:⩽ geqslant:⩾ lneq:⪇ gneq:⪈ ncong:≇ " +
            "nsim:≁ napprox:≉ nequiv:≢ nless:≮ ngtr:≯ nleq:≰ ngeq:≱ approxeq:≊ backsim:∽ thicksim:∼ thickapprox:≈ " +
            "Subset:⋐ Supset:⋑ subseteqq:⫅ supseteqq:⫆ precsim:≾ succsim:≿ " +
            // arrows
            "to:→ rightarrow:→ gets:← leftarrow:← leftrightarrow:↔ Rightarrow:⇒ Leftarrow:⇐ Leftrightarrow:⇔ " +
            "longrightarrow:⟶ longleftarrow:⟵ longleftrightarrow:⟷ Longrightarrow:⟹ Longleftarrow:⟸ " +
            "Longleftrightarrow:⟺ iff:⟺ implies:⟹ impliedby:⟸ mapsto:↦ longmapsto:⟼ uparrow:↑ downarrow:↓ " +
            "updownarrow:↕ Uparrow:⇑ Downarrow:⇓ Updownarrow:⇕ nearrow:↗ searrow:↘ swarrow:↙ nwarrow:↖ " +
            "hookrightarrow:↪ hookleftarrow:↩ rightharpoonup:⇀ rightharpoondown:⇁ leftharpoonup:↼ " +
            "leftharpoondown:↽ rightleftharpoons:⇌ leftrightharpoons:⇋ twoheadrightarrow:↠ twoheadleftarrow:↞ " +
            "rightarrowtail:↣ leftarrowtail:↢ rightsquigarrow:⇝ leadsto:⇝ leftrightsquigarrow:↭ " +
            "rightleftarrows:⇄ leftrightarrows:⇆ rightrightarrows:⇉ leftleftarrows:⇇ Rrightarrow:⇛ Lleftarrow:⇚ " +
            "curvearrowright:↷ curvearrowleft:↶ circlearrowright:↻ circlearrowleft:↺ nrightarrow:↛ nleftarrow:↚ " +
            "nRightarrow:⇏ nLeftarrow:⇍ nleftrightarrow:↮ nLeftrightarrow:⇎ upharpoonright:↾ upharpoonleft:↿ " +
            "downharpoonright:⇂ downharpoonleft:⇃ multimap:⊸"
    )

    val ordinary = table(
        "infty:∞ partial:∂ nabla:∇ forall:∀ exists:∃ nexists:∄ neg:¬ lnot:¬ emptyset:∅ varnothing:∅ hbar:ℏ hslash:ℏ " +
            "ell:ℓ Re:ℜ Im:ℑ aleph:ℵ beth:ℶ gimel:ℷ daleth:ℸ wp:℘ prime:′ backprime:‵ angle:∠ measuredangle:∡ " +
            "sphericalangle:∢ triangle:△ square:□ blacksquare:■ Box:□ Diamond:◇ lozenge:◊ blacklozenge:⧫ top:⊤ bot:⊥ " +
            "surd:√ flat:♭ natural:♮ sharp:♯ clubsuit:♣ diamondsuit:♢ heartsuit:♡ spadesuit:♠ degree:° " +
            "checkmark:✓ S:§ P:¶ copyright:© pounds:£ yen:¥ euro:€ imath:ı jmath:ȷ complement:∁ mho:℧ " +
            "eth:ð bigstar:★ blacktriangle:▲ blacktriangledown:▼ triangledown:▽ vert:| Vert:‖ " +
            "textbackslash:\\ backslash:\\"
    )

    val dots = table("ldots:… dots:… cdots:⋯ vdots:⋮ ddots:⋱ adots:⋰ dotsc:… dotsb:⋯ dotsm:⋯ dotsi:⋯ dotso:… mathellipsis:… textellipsis:…")

    val opens = table(
        "lbrace:{ langle:⟨ lfloor:⌊ lceil:⌈ lbrack:[ lvert:| lVert:‖ llbracket:⟦ lgroup:⟮ ulcorner:⌜ llcorner:⌞"
    )
    val closes = table(
        "rbrace:} rangle:⟩ rfloor:⌋ rceil:⌉ rbrack:] rvert:| rVert:‖ rrbracket:⟧ rgroup:⟯ urcorner:⌝ lrcorner:⌟"
    )

    /** \sum-like operators: glyph and whether limits go above/below in display style. */
    val largeOps = mapOf(
        "sum" to ("∑" to true), "prod" to ("∏" to true), "coprod" to ("∐" to true),
        "bigcup" to ("⋃" to true), "bigcap" to ("⋂" to true), "bigvee" to ("⋁" to true), "bigwedge" to ("⋀" to true),
        "bigoplus" to ("⨁" to true), "bigotimes" to ("⨂" to true), "bigodot" to ("⨀" to true),
        "biguplus" to ("⨄" to true), "bigsqcup" to ("⨆" to true),
        "int" to ("∫" to false), "iint" to ("∬" to false), "iiint" to ("∭" to false), "oint" to ("∮" to false),
        "oiint" to ("∯" to false), "oiiint" to ("∰" to false), "intop" to ("∫" to false),
    )

    /** Upright operator names. The flag says that limits go above/below in display style. */
    val namedOps = mapOf(
        "sin" to ("sin" to false), "cos" to ("cos" to false), "tan" to ("tan" to false), "cot" to ("cot" to false),
        "sec" to ("sec" to false), "csc" to ("csc" to false), "arcsin" to ("arcsin" to false),
        "arccos" to ("arccos" to false), "arctan" to ("arctan" to false), "arccot" to ("arccot" to false),
        "sinh" to ("sinh" to false), "cosh" to ("cosh" to false), "tanh" to ("tanh" to false), "coth" to ("coth" to false),
        "sech" to ("sech" to false), "csch" to ("csch" to false),
        "log" to ("log" to false), "ln" to ("ln" to false), "lg" to ("lg" to false), "exp" to ("exp" to false),
        "deg" to ("deg" to false), "dim" to ("dim" to false), "ker" to ("ker" to false), "hom" to ("hom" to false),
        "arg" to ("arg" to false), "Pr" to ("Pr" to true), "det" to ("det" to true), "gcd" to ("gcd" to true),
        "inf" to ("inf" to true), "sup" to ("sup" to true), "lim" to ("lim" to true),
        "liminf" to ("lim inf" to true), "limsup" to ("lim sup" to true),
        "max" to ("max" to true), "min" to ("min" to true), "mod" to ("mod" to false),
        "injlim" to ("inj lim" to true), "projlim" to ("proj lim" to true),
    )

    val accents = mapOf(
        "hat" to "\u02C6", "widehat" to "\u02C6", "tilde" to "\u0303", "widetilde" to "\u0303", "bar" to "\u00AF",
        "dot" to "\u0307", "ddot" to "\u0308", "dddot" to "\u20DB", "vec" to "→", "acute" to "\u0301",
        "grave" to "\u0060", "check" to "\u030C", "breve" to "\u0306", "mathring" to "\u02DA",
    )

    val spaces = mapOf(
        "," to 3f / 18f, ":" to 4f / 18f, ">" to 4f / 18f, ";" to 5f / 18f, "!" to -3f / 18f, " " to 0.25f,
        "quad" to 1f, "qquad" to 2f, "enspace" to 0.5f, "thinspace" to 3f / 18f, "medspace" to 4f / 18f,
        "thickspace" to 5f / 18f, "negthinspace" to -3f / 18f, "negmedspace" to -4f / 18f, "negthickspace" to -5f / 18f,
        "/" to 0f, "@" to 0f, "-" to 0f,
    )

    /** Delimiter command/character -> displayed glyph. */
    val delimiters: Map<String, String> = run {
        val m = HashMap<String, String>()
        for (c in "()[]|/<>") m[c.toString()] = c.toString().let { if (it == "<") "⟨" else if (it == ">") "⟩" else it }
        m["{"] = "{"; m["}"] = "}"; m["\\{"] = "{"; m["\\}"] = "}"; m["\\|"] = "‖"; m["\\\\"] = "\\"
        for ((k, v) in opens) m["\\$k"] = v
        for ((k, v) in closes) m["\\$k"] = v
        m["\\vert"] = "|"; m["\\Vert"] = "‖"; m["\\backslash"] = "\\"
        m["\\uparrow"] = "↑"; m["\\downarrow"] = "↓"; m["\\Uparrow"] = "⇑"; m["\\Downarrow"] = "⇓"; m["\\updownarrow"] = "↕"
        m["\\Updownarrow"] = "⇕"; m["\\lmoustache"] = "⎰"; m["\\rmoustache"] = "⎱"
        m
    }

    val colors = mapOf(
        "red" to 0xFFFF6B6B.toInt(), "blue" to 0xFF6EA8FF.toInt(), "green" to 0xFF5FD98A.toInt(), "orange" to 0xFFFFA94D.toInt(),
        "purple" to 0xFFC792EA.toInt(), "violet" to 0xFFC792EA.toInt(), "magenta" to 0xFFFF79C6.toInt(), "pink" to 0xFFFF9EC4.toInt(),
        "cyan" to 0xFF5FD7E8.toInt(), "teal" to 0xFF4DD0C4.toInt(), "yellow" to 0xFFFFE066.toInt(), "brown" to 0xFFC49A6C.toInt(),
        "gray" to 0xFF9A9A9A.toInt(), "grey" to 0xFF9A9A9A.toInt(), "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(),
        "lime" to 0xFFB5E853.toInt(), "olive" to 0xFFB5B54D.toInt(), "gold" to 0xFFFFC857.toInt(),
    )

    private val reverseClass: Map<Int, Cls> = run {
        val m = HashMap<Int, Cls>()
        fun add(t: Map<String, String>, c: Cls) { for (v in t.values) if (v.codePointCount(0, v.length) == 1) m.putIfAbsent(v.codePointAt(0), c) }
        add(binary, Cls.BIN); add(relation, Cls.REL); add(opens, Cls.OPEN); add(closes, Cls.CLOSE)
        for ((_, v) in largeOps) m.putIfAbsent(v.first.codePointAt(0), Cls.OP)
        m.remove('|'.code)
        m
    }

    /** Class of a symbol typed directly as a Unicode character. */
    fun classOf(codePoint: Int): Cls = reverseClass[codePoint] ?: Cls.ORD

    fun isLatinLetter(cp: Int) = cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code
    fun isGreekLetter(cp: Int) = cp in 0x391..0x3A9 || cp in 0x3B1..0x3C9 || cp == 0x3D1 || cp == 0x3D5 || cp == 0x3D6 || cp == 0x3F0 || cp == 0x3F1 || cp == 0x3F5
    fun isDigit(cp: Int) = cp in '0'.code..'9'.code

    // Letterlike holes in the Unicode math alphabets (code points that live elsewhere).
    private val scriptHoles = mapOf('B' to 0x212C, 'E' to 0x2130, 'F' to 0x2131, 'H' to 0x210B, 'I' to 0x2110, 'L' to 0x2112, 'M' to 0x2133, 'R' to 0x211B, 'e' to 0x212F, 'g' to 0x210A, 'o' to 0x2134)
    private val frakturHoles = mapOf('C' to 0x212D, 'H' to 0x210C, 'I' to 0x2111, 'R' to 0x211C, 'Z' to 0x2128)
    private val doubleHoles = mapOf('C' to 0x2102, 'H' to 0x210D, 'N' to 0x2115, 'P' to 0x2119, 'Q' to 0x211A, 'R' to 0x211D, 'Z' to 0x2124)

    private fun latin(cp: Int, upper: Int, lower: Int, holes: Map<Char, Int> = emptyMap()): Int {
        holes[cp.toChar()]?.let { return it }
        return if (cp in 'A'.code..'Z'.code) upper + (cp - 'A'.code) else lower + (cp - 'a'.code)
    }

    private fun greek(cp: Int, upperBase: Int, lowerBase: Int): Int = when {
        cp in 0x391..0x3A9 -> upperBase + (cp - 0x391)
        cp in 0x3B1..0x3C9 -> lowerBase + (cp - 0x3B1)
        cp == 0x3F5 -> lowerBase + 26 // epsilon (lunate)
        cp == 0x3D1 -> lowerBase + 27 // theta symbol
        cp == 0x3F0 -> lowerBase + 28 // kappa symbol
        cp == 0x3D5 -> lowerBase + 29 // phi symbol
        cp == 0x3F1 -> lowerBase + 30 // rho symbol
        cp == 0x3D6 -> lowerBase + 31 // pi symbol
        else -> cp
    }

    /** Maps one code point into the requested alphabet; characters without a styled form come back unchanged. */
    fun styled(cp: Int, variant: Variant): Int {
        val latin = isLatinLetter(cp)
        val greek = isGreekLetter(cp)
        val digit = isDigit(cp)
        return when (variant) {
            Variant.ROMAN, Variant.ITALIC -> if (variant == Variant.ITALIC && latin) latin(cp, 0x1D434, 0x1D44E).let { if (cp == 'h'.code) 0x210E else it } else cp
            Variant.DEFAULT -> when {
                latin -> if (cp == 'h'.code) 0x210E else latin(cp, 0x1D434, 0x1D44E)
                greek && cp !in 0x391..0x3A9 -> greek(cp, 0x1D6E2, 0x1D6FC)
                else -> cp
            }
            Variant.BOLD -> when {
                latin -> latin(cp, 0x1D400, 0x1D41A)
                greek -> greek(cp, 0x1D6A8, 0x1D6C2)
                digit -> 0x1D7CE + (cp - '0'.code)
                else -> cp
            }
            Variant.BOLD_ITALIC -> when {
                latin -> latin(cp, 0x1D468, 0x1D482)
                greek -> greek(cp, 0x1D71C, 0x1D736)
                digit -> 0x1D7CE + (cp - '0'.code)
                else -> cp
            }
            Variant.SCRIPT -> if (latin) latin(cp, 0x1D49C, 0x1D4B6, scriptHoles) else cp
            Variant.FRAKTUR -> if (latin) latin(cp, 0x1D504, 0x1D51E, frakturHoles) else cp
            Variant.DOUBLE_STRUCK -> when {
                latin -> latin(cp, 0x1D538, 0x1D552, doubleHoles)
                digit -> 0x1D7D8 + (cp - '0'.code)
                else -> cp
            }
            Variant.SANS -> when {
                latin -> latin(cp, 0x1D5A0, 0x1D5BA)
                digit -> 0x1D7E2 + (cp - '0'.code)
                else -> cp
            }
            Variant.MONO -> when {
                latin -> latin(cp, 0x1D670, 0x1D68A)
                digit -> 0x1D7F6 + (cp - '0'.code)
                else -> cp
            }
        }
    }

    fun styled(text: String, variant: Variant): String {
        if (variant == Variant.ROMAN) return text
        val sb = StringBuilder(text.length + 4)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            sb.appendCodePoint(styled(cp, variant))
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
