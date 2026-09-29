package hk.eduhk.typeduck.ime.text

import hk.eduhk.typeduck.data.AppPrefs
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

data class CandidateEntry(
	var matchInputBuffer: String? = null,
	var honzi: String? = null,
	var jyutping: String? = null,
	var pronOrder: String? = null,
	var sandhi: String? = null,
	var litColReading: String? = null,
	var properties: Properties = Properties(),
) {

	data class Properties(
		var partOfSpeech: String? = null,
		var register: String? = null,
		var label: String? = null,
		var normalized: String? = null,
		var written: String? = null,
		var vernacular: String? = null,
		var collocation: String? = null,
		var definition: Definition = Definition(),
	)

	data class Definition(
		var eng: String? = null,
		var urd: String? = null,
		var nep: String? = null,
		var hin: String? = null,
		var ind: String? = null,
	)

	private val pref = AppPrefs.defaultInstance()
	private val prefMainLanguage = pref.typeDuck.mainLanguage
	private val prefDisplayLanguages = pref.typeDuck.displayLanguages

	companion object {
		private val columns = listOf(
			(CandidateEntry::matchInputBuffer).asProperty(),
			(CandidateEntry::honzi).asProperty(),
			(CandidateEntry::jyutping).asProperty(),
			(CandidateEntry::pronOrder).asProperty(),
			(CandidateEntry::sandhi).asProperty(),
			(CandidateEntry::litColReading).asProperty(),
			(CandidateEntry::properties)(Properties::partOfSpeech),
			(CandidateEntry::properties)(Properties::register),
			(CandidateEntry::properties)(Properties::label),
			(CandidateEntry::properties)(Properties::normalized),
			(CandidateEntry::properties)(Properties::written),
			(CandidateEntry::properties)(Properties::vernacular),
			(CandidateEntry::properties)(Properties::collocation),
			(CandidateEntry::properties)(Properties::definition)(Definition::eng),
			(CandidateEntry::properties)(Properties::definition)(Definition::urd),
			(CandidateEntry::properties)(Properties::definition)(Definition::nep),
			(CandidateEntry::properties)(Properties::definition)(Definition::hin),
			(CandidateEntry::properties)(Properties::definition)(Definition::ind),
		)

		private val checkColumns = listOf(
			(CandidateEntry::properties)(Properties::partOfSpeech),
			(CandidateEntry::properties)(Properties::register),
			(CandidateEntry::properties)(Properties::normalized),
			(CandidateEntry::properties)(Properties::written),
			(CandidateEntry::properties)(Properties::vernacular),
			(CandidateEntry::properties)(Properties::collocation),
		)

		val otherData = listOf(
			"Standard Form 標準字形" to (CandidateEntry::properties)(Properties::normalized),
			"Written Form 書面語" to (CandidateEntry::properties)(Properties::written),
			"Vernacular Form 口語" to (CandidateEntry::properties)(Properties::vernacular),
			"Collocation 配搭" to (CandidateEntry::properties)(Properties::collocation),
		)

		val litColReading = mapOf(
			"lit" to "literary reading 文讀",
			"col" to "colloquial reading 白讀",
		)

		val register = mapOf(
			"wri" to "written 書面語 ",
			"ver" to "vernacular 口語 ",
			"for" to "formal 公文體 ",
			"lzh" to "classical Chinese 文言 ",
		)

		val partOfSpeech = mapOf(
			"n" to "noun 名詞",
			"v" to "verb 動詞",
			"adj" to "adjective 形容詞",
			"adv" to "adverb 副詞",
			"morph" to "morpheme 語素",
			"mw" to "measure word 量詞",
			"part" to "particle 助詞",
			"oth" to "other 其他",
			"x" to "non-morpheme 非語素",
		)

		val labels = mapOf(
			"abbrev" to "abbreviation 簡稱",
			"astro" to "astronomy 天文",
			"ChinMeta" to "sexagenary cycle 干支",
			"horo" to "horoscope 星座",
			"org" to "organisation 機構",
			"person" to "person 人名",
			"place" to "place 地名",
			"reli" to "religion 宗教",
			"rare" to "rare 罕見",
			"composition" to "compound 詞組",
		)
	}

	var isJyutpingOnly = true

	constructor(csv: String) : this() {
		isJyutpingOnly = false
		val charIterator = csv.iterator().asPeekable()
		val columnIterator = columns.iterator()
		var isQuoted = false
		var column = columnIterator.next()
		val value = StringBuilder()
		while (charIterator.hasNext()) {
			val char = charIterator.next()
			if (isQuoted) {
				if (char == '"') {
					if (charIterator.peek() == '"') {
						value.append(charIterator.next())
					} else {
						isQuoted = false
					}
				} else {
					value.append(char)
				}
			} else if (value.isBlank() && char == '"') {
				isQuoted = true
			} else if (char == ',') {
				if (!columnIterator.hasNext()) {
					break
				}
				if (value.isNotBlank()) {
					this[column] = value.toString()
				}
				column = columnIterator.next()
				value.clear()
			} else {
				value.append(char)
			}
		}
		if (value.isNotBlank()) {
			this[column] = value.toString()
		}
		if (jyutping != null) {
			val jyutpingIterator = jyutping!!.iterator()
			value.clear()
			while (jyutpingIterator.hasNext()) {
				val char = jyutpingIterator.next()
				value.append(char)
				if (char.isDigit() && jyutpingIterator.hasNext()) {
					value.append(' ')
				}
			}
			jyutping = value.toString()
		}
	}

	fun getDefinition(language: Language): String? {
		return when (language) {
			Language.ENG -> properties.definition.eng
			Language.HIN -> properties.definition.hin
			Language.IND -> properties.definition.ind
			Language.NEP -> properties.definition.nep
			Language.URD -> properties.definition.urd
		}
	}

	val mainLanguage: String?
		get() = getDefinition(prefMainLanguage)

	val englishDefinition: String?
		get() = getDefinition(Language.ENG)

	private val firstAvailableDefinition: String?
		get() = Language.values().firstNotNullOfOrNull { getDefinition(it) }

	val otherLanguages: List<String>
		get() = prefDisplayLanguages
			.filter { it != prefMainLanguage }
			.mapNotNull { getDefinition(it) }

	val otherLanguagesWithNames: List<Pair<String, String>>
		get() = prefDisplayLanguages
			.filter { it != prefMainLanguage }
			.mapNotNull { language ->
				getDefinition(language)?.let { language.displayName to it }
			}

	val formattedLabels: List<String>?
		get() = properties.label?.split(" ")?.map { "($it)" }

	val mainLanguageOrLabel: String?
		get() = if (isDictionaryEntry) {
			mainLanguage ?: englishDefinition ?: firstAvailableDefinition
		} else {
			formattedLabels?.joinToString(" ")
		}

	val otherLanguagesOrLabels: List<String>
		get() = if (isDictionaryEntry) otherLanguages else formattedLabels.orEmpty()

	val isDictionaryEntry: Boolean
		get() = !isJyutpingOnly && (
			checkColumns.any { this[it] != null } ||
			Language.values().any { getDefinition(it) != null }
		)
}

fun <T> Iterator<T>.asPeekable(): PeekableIterator<T> = PeekableIterator(this)

class PeekableIterator<T>(private val iterator: Iterator<T>) : Iterator<T> {
	private var nextElement: T? = null

	fun peek(): T? {
		if (nextElement == null) {
			nextElement = if (iterator.hasNext()) iterator.next() else null
		}
		return nextElement
	}

	override fun hasNext() = nextElement != null || iterator.hasNext()

	override fun next(): T {
		val result = nextElement ?: iterator.next()
		nextElement = null
		return result
	}
}

// https://discuss.kotlinlang.org/t/chaining-property-accessors-and-maintaining-both-the-ability-to-read-and-write/24322
operator fun <T, V> T.get(key: Property<T, V>): V = key.get(this)
operator fun <T, V> T.set(key: MutableProperty<T, V>, value: V) = key.set(this, value)

operator fun <T, U, V> KProperty1<T, U>.invoke(next: KMutableProperty1<U, V>): MutableProperty<T, V> = asProperty()(next.asProperty())
operator fun <T, U, V> Property<T, U>.invoke(next: KMutableProperty1<U, V>): MutableProperty<T, V> = this(next.asProperty())
operator fun <T, U, V> Property<T, U>.invoke(next: MutableProperty<U, V>): MutableProperty<T, V> = object : MutableProperty<T, V> {
	override fun get(receiver: T): V = next.get(this@invoke.get(receiver))
	override fun set(receiver: T, value: V) = next.set(this@invoke.get(receiver), value)
}

fun <T, V> KProperty1<T, V>.asProperty(): Property<T, V> = object : Property<T, V> {
	override fun get(receiver: T): V = this@asProperty.get(receiver)
}

fun <T, V> KMutableProperty1<T, V>.asProperty(): MutableProperty<T, V> = object : MutableProperty<T, V> {
	override fun get(receiver: T): V = this@asProperty.get(receiver)
	override fun set(receiver: T, value: V) = this@asProperty.set(receiver, value)
}

interface Property<in T, out V> {
	fun get(receiver: T): V
}

interface MutableProperty<in T, V> : Property<T, V> {
	fun set(receiver: T, value: V)
}