package com.tvoshome.app

/**
 * Chooses the source language among the few languages the user cares about (Settings), so a short
 * subtitle is not guessed as some unrelated language. The language of the previous sentences is kept
 * unless the new text clearly points somewhere else.
 */
object LangDetect {
    /** Bit order = order of the language chips in Settings. */
    val CODES = arrayOf("en", "tr", "sr", "hr", "sq", "bs", "fr", "pt", "es", "bg", "hu")
    private const val SR = 2
    private const val HR = 3
    private const val BS = 5
    private const val BG = 9
    const val ALL = 2047

    private fun w(s: String): Set<String> = s.split(' ').filter { it.isNotEmpty() }.toSet()

    private val STOP: Array<Set<String>> = arrayOf(
        w("the and is you that it was for are with his they this have from not but what all were when your can said there each which she how will about out many then them these some her would make like him into time has look more see way could people my than been who now find did get come made may"),
        w("bir ve bu da de için ne ben sen ama çok var mı mi mu değil gibi daha o şey ile ki evet hayır biz siz onu bana sana nasıl neden olarak kadar sonra şimdi burada orada yok tamam lütfen teşekkür her bunu şu çünkü ise ya"),
        w("je i u da se na ne to sam si smo ste su za od sa ali šta što kako ovo ono ja ti mi vi oni bio bila nije nisam nemoj ovde tamo sada već jer ako kad gde ko zašto molim hvala dobro ću ćeš hoću hteo и је у да се на не то сам си смо сте су за од са али шта што како ово оно ја ти ми ви они био била није нисам овде тамо сада већ јер ако кад где ко зашто молим хвала добро ћу"),
        w("je i u da se na ne to sam si smo ste su za od sa ali što kako ovo ono ja ti mi vi oni bio bila nije nisam ovdje tamo sada već jer ako kad gdje tko zašto molim hvala dobro ću ćeš hoću htio mislim znam trebam možeš može"),
        w("dhe një është në të që për me nuk jam ti ai ajo ne ju ata kjo ky po jo si çfarë ku kur pse kush shumë mirë faleminderit lutem nga do kam ke ka kemi keni kanë por edhe këtu atje tani tashmë ishte ishin mund duhet"),
        w("je i u da se na ne to sam si smo ste su za od sa ali šta kako ovo ono ja ti mi vi oni nije nisam ovdje tamo sad već jer ako kad gdje ko zašto molim hvala dobro hoću mogu"),
        w("le la les un une des et est que qui dans pour pas ne vous nous je tu il elle ils ce cette avec sur mais ou où comme plus tout être avoir fait sont au du de en ça oui non merci très bien aussi mon ma mes c'est j'ai qu'il"),
        w("o a os as um uma e é que de do da em no na para com não nao eu você voce ele ela nós eles mas por mais como está estou são foi tem tenho muito bem obrigado sim aqui isso esse essa meu minha ao dos das"),
        w("el la los las un una y es que de en no por para con se lo su al como más pero yo tú usted él ella nosotros ellos esto eso está estoy son fue tiene tengo muy bien gracias sí aquí mi mis qué cómo dónde cuándo porque hay"),
        w("и в на е да не се за с от че са това как той тя ние вие те аз ти но или по като съм си сме сте бях беше има няма много добре благодаря тук там сега защо кога къде кой какво ще ли"),
        w("a az és egy hogy nem de én te ő mi ti ők van volt lesz is ez itt ott most már még csak nagyon jó igen köszönöm kérem mit hol mikor miért ki vagyok vagy vagyunk vannak meg el fel le be")
    )

    /** Words that tell Serbian / Croatian / Bosnian apart. */
    private val SPEC: Array<Set<String>> = arrayOf(
        emptySet(), emptySet(),
        w("gde ovde sada šta hteo reč где овде сада шта"),
        w("što gdje tko ovdje htio tisuća"),
        emptySet(),
        w("šta gdje ko sad hoću"),
        emptySet(), emptySet(), emptySet(), emptySet(), emptySet()
    )

    /** Letters that point to a language. */
    private val LETTERS: Array<String> = arrayOf("", "ğışİ", "јљњћђџđćčšž", "đćčšž", "ë", "đćčšž", "éèêàùœîô", "ãõ", "ñ¿¡", "ъщ", "őű")

    private val SPLIT = Regex("[^\\p{L}'’]+")

    fun isEnabled(code: String, mask: Int): Boolean {
        val i = CODES.indexOf(code)
        return i >= 0 && ((mask shr i) and 1) == 1
    }

    /** OCR scripts needed for the enabled languages (Latin always; Cyrillic for Serbian / Bulgarian). */
    fun scripts(mask: Int): List<String> {
        val out = arrayListOf("Latin")
        if (((mask shr SR) and 1) == 1 || ((mask shr BG) and 1) == 1) out.add("Cyrillic")
        return out
    }

    class Det(val code: String?, val score: Int)

    fun enabledCount(mask: Int): Int {
        var n = 0
        for (i in CODES.indices) if (((mask shr i) and 1) == 1) n++
        return n
    }

    /** Serbian, Croatian and Bosnian are told apart only on paper: any of them stands for the others. */
    private val CLUSTER = setOf("sr", "hr", "bs", "sh", "cnr")

    /** The selected language that a translator's detected language ("hr", "sr-Latn", "sh" ...) corresponds to, or null. */
    fun canonical(src: String, mask: Int): String? {
        if (src.isEmpty()) return null
        val s = src.lowercase().substringBefore('-')
        if (isEnabled(s, mask)) return s
        if (s in CLUSTER) for (c in arrayOf("sr", "hr", "bs")) if (isEnabled(c, mask)) return c
        return null
    }

    fun accepts(src: String, mask: Int): Boolean = canonical(src, mask) != null

    fun detect(text: String, mask: Int, current: String?): String? = detectScored(text, mask, current).code

    /** Language among the selected ones, with how much evidence supports it (0 = none). */
    fun detectScored(text: String, mask: Int, current: String?): Det {
        var letters = 0
        var cyr = 0
        for (ch in text) if (ch.isLetter()) { letters++; if (ch.code in 0x0400..0x04FF) cyr++ }
        if (letters < 3) return Det(current, 0)
        val cyrMode = cyr * 2 > letters
        val cand = ArrayList<Int>()
        for (i in CODES.indices) {
            if (((mask shr i) and 1) == 0) continue
            if (cyrMode && i != SR && i != BG) continue
            if (!cyrMode && i == BG) continue
            cand.add(i)
        }
        if (cand.isEmpty()) return Det(null, 0)
        if (cand.size == 1) return Det(CODES[cand[0]], 0)
        val lower = text.lowercase()
        val words = lower.split(SPLIT).filter { it.isNotEmpty() }
        val score = IntArray(CODES.size)
        for (i in cand) {
            var sc = 0
            for (wd in words) {
                if (wd in STOP[i]) sc += 1
                if (wd in SPEC[i]) sc += 2
            }
            val lt = LETTERS[i]
            if (lt.isNotEmpty()) {
                var lc = 0
                for (ch in lower) if (lt.indexOf(ch) >= 0) lc++
                sc += minOf(lc, 4) * 2
            }
            score[i] = sc
        }
        var best = cand[0]
        for (i in cand) if (score[i] > score[best]) best = i
        val curIdx = if (current == null) -1 else CODES.indexOf(current)
        val curOk = curIdx >= 0 && cand.contains(curIdx)
        if (score[best] < 2) return if (curOk) Det(CODES[curIdx], score[curIdx]) else Det(null, 0)
        if (curOk && curIdx != best) {
            val inCluster = (best == SR || best == HR || best == BS) && (curIdx == SR || curIdx == HR || curIdx == BS)
            if (inCluster) return Det(CODES[curIdx], score[curIdx])
            if (score[best].toFloat() < score[curIdx] * 1.4f + 1f) return Det(CODES[curIdx], score[curIdx])
        }
        return Det(CODES[best], score[best])
    }
}
