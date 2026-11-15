package com.tvoshome.app

import java.text.Normalizer

/** Built-in list of national teams: Arabic name, flag and common spellings in many languages. */
object NationalTeams {
    class NT(val iso: String, val en: String, val ar: String) {
        val flagUrl: String get() = "https://flagcdn.com/w160/" + iso + ".png"
    }

    private val DIAC = Regex("\\p{M}+")
    private val ARDIAC = Regex("[\\u064B-\\u065F\\u0670]")
    private val NONL = Regex("[^\\p{L}\\p{N}]")

    /** Language-independent key: lower case, no accents, Arabic letter variants merged, no spaces / symbols. */
    fun key(s: String): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        t = DIAC.replace(t, "")
        t = ARDIAC.replace(t, "")
        t = t.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')
        t = NONL.replace(t, "")
        if (t.startsWith("ال") && t.length > 4) t = t.substring(2)
        return t
    }

    private val DATA = """
al|Albania|ألبانيا|ألبانيا;البانيا;Shqipëria;Albanien;Albania;Албания;אלבניה
ad|Andorra|أندورا|Andorre
am|Armenia|أرمينيا|Armenien;Armenie;Армения;ארמניה
at|Austria|النمسا|Österreich;Osterreich;Autriche;Austria;Австрия;אוסטריה
az|Azerbaijan|أذربيجان|Azerbaïdjan;Aserbaidschan;Azerbaigian;Азербайджан;אזרבייג'ן
by|Belarus|بيلاروسيا|Bielorussia;Weissrussland;Biélorussie;Беларусь;Белоруссия;בלארוס
be|Belgium|بلجيكا|België;Belgique;Belgien;Belgio;Bélgica;בלגיה
ba|Bosnia and Herzegovina|البوسنة والهرسك|Bosnia;Bosnia Herzegovina;Bosna i Hercegovina;Bosnien;Босна и Херцеговина;בוסניה
bg|Bulgaria|بلغاريا|България;Bulgarie;Bulgarien;Bulgaria;בולגריה
hr|Croatia|كرواتيا|Hrvatska;Kroatien;Croatie;Croazia;Хрватска;קרואטיה
cy|Cyprus|قبرص|Zypern;Chypre;Cipro;Κύπρος;קפריסין
cz|Czech Republic|التشيك|Czechia;Czech Rep;Česko;Cesko;Tschechien;Tchéquie;Repubblica Ceca;Chequia;Чехия;צ'כיה;تشيكيا;جمهورية التشيك
dk|Denmark|الدنمارك|Danmark;Dänemark;Danemark;Danimarca;Dinamarca;דנמרק
gb-eng|England|إنجلترا|Inglaterra;Angleterre;Inghilterra;אנגליה;انجلترا
ee|Estonia|إستونيا|Eesti;Estland;Estonie;אסטוניה;استونيا
fo|Faroe Islands|جزر فارو|Faroe;Färöer;Føroyar
fi|Finland|فنلندا|Suomi;Finnland;Finlande;Finlandia;פינלנד
fr|France|فرنسا|Frankreich;Francia;Франция;צרפת
ge|Georgia|جورجيا|Georgien;Géorgie;Georgia;Грузия;גאורגיה
de|Germany|ألمانيا|Deutschland;Allemagne;Germania;Alemania;Duitsland;Германия;גרמניה;المانيا
gi|Gibraltar|جبل طارق|
gr|Greece|اليونان|Hellas;Griechenland;Grèce;Grecia;Grecja;Ελλάδα;Греция;יוון
hu|Hungary|المجر|Magyarország;Ungarn;Hongrie;Ungheria;Hungría;Венгрия;הונגריה;هنغاريا
is|Iceland|آيسلندا|Island;Islande;Islanda;Islandia;Исландия;איסלנד;ايسلندا
il|Israel|إسرائيل|ישראל;Izrael;Израиль;اسرائيل
it|Italy|إيطاليا|Italia;Italien;Italie;Italië;Италия;איטליה;ايطاليا
kz|Kazakhstan|كازاخستان|Kazakistan;Kasachstan;Kazajistán;Казахстан;קזחסטן;كازاخستان
xk|Kosovo|كوسوفو|Kosova;Косово;קוסובו
lv|Latvia|لاتفيا|Latvija;Lettland;Lettonie;Lettonia;לטביה
li|Liechtenstein|ليختنشتاين|
lt|Lithuania|ليتوانيا|Lietuva;Litauen;Lituanie;Lituania;ליטא
lu|Luxembourg|لوكسمبورغ|Luxemburg;Lussemburgo;לוקסמבורג
mt|Malta|مالطا|
md|Moldova|مولدوفا|Moldavia;Moldawien;Moldavie;Republic of Moldova;Молдова;מולדובה
me|Montenegro|الجبل الأسود|Crna Gora;Черна Гора;מונטנגרו
nl|Netherlands|هولندا|Holland;Nederland;Niederlande;Pays-Bas;Paesi Bassi;Países Bajos;Нидерланды;הולנד
mk|North Macedonia|مقدونيا الشمالية|Macedonia;Makedonija;Mazedonien;Nordmazedonien;Македонија;צפון מקדוניה;مقدونيا
gb-nir|Northern Ireland|أيرلندا الشمالية|Nordirland;Irlande du Nord;Irlanda del Nord;ايرلندا الشمالية
no|Norway|النرويج|Norge;Norwegen;Norvège;Norvegia;Noruega;Норвегия;נורווגיה
pl|Poland|بولندا|Polska;Polen;Pologne;Polonia;Польша;פולין
pt|Portugal|البرتغال|Португалия;פורטוגל
ie|Republic of Ireland|أيرلندا|Ireland;Eire;Irlanda;Irland;Irlande;Rep of Ireland;אירלנד;ايرلندا
ro|Romania|رومانيا|România;Rumänien;Roumanie;Румыния;רומניה
ru|Russia|روسيا|Rusia;Russland;Russie;Russland;Россия;רוסיה
sm|San Marino|سان مارينو|
gb-sct|Scotland|اسكتلندا|Escocia;Schottland;Écosse;Scozia;סקוטלנד
rs|Serbia|صربيا|Srbija;Србија;Serbien;Serbie;Serbia;Сербия;סרביה
sk|Slovakia|سلوفاكيا|Slovensko;Slowakei;Slovaquie;Slovacchia;Eslovaquia;Словакия;סלובקיה
si|Slovenia|سلوفينيا|Slovenija;Slowenien;Slovénie;Slovenia;Eslovenia;סלובניה
es|Spain|إسبانيا|España;Spanien;Espagne;Spagna;Spanje;Испания;ספרד;اسبانيا
se|Sweden|السويد|Sverige;Schweden;Suède;Svezia;Suecia;Швеция;שוודיה
ch|Switzerland|سويسرا|Schweiz;Suisse;Svizzera;Suiza;Швейцария;שווייץ
tr|Turkey|تركيا|Türkiye;Turkiye;Türkei;Turquie;Turchia;Turquía;Турция;טורקיה
ua|Ukraine|أوكرانيا|Ukraina;Україна;Украина;אוקראינה;اوكرانيا
gb-wls|Wales|ويلز|Galles;Gales;Cymru;וויילס
eg|Egypt|مصر|Egipto;Ägypten;Égypte;Egitto;מצרים
sa|Saudi Arabia|السعودية|KSA;Arabia Saudita;Saudi;Arabie saoudite;Saudi-Arabien;ערב הסעודית;المملكة العربية السعودية
ma|Morocco|المغرب|Maroc;Marokko;Marocco;Marruecos;Марокко;מרוקו
dz|Algeria|الجزائر|Algérie;Algerien;Algeria;Argelia;אלג'יריה
tn|Tunisia|تونس|Tunisie;Tunesien;Tunisia;Túnez;טוניסיה
ly|Libya|ليبيا|Libye;Libyen;Libia
sd|Sudan|السودان|Soudan;Sudán
iq|Iraq|العراق|Irak;Iraq;Iraque
sy|Syria|سوريا|Syrie;Syrien;Siria;Siria
jo|Jordan|الأردن|Jordanie;Jordanien;Giordania;Jordania;الاردن
lb|Lebanon|لبنان|Liban;Libanon;Libano
ps|Palestine|فلسطين|Palästina;Palestina
kw|Kuwait|الكويت|Koweït;Kuwait
qa|Qatar|قطر|Katar
ae|United Arab Emirates|الإمارات|UAE;Emirates;الامارات;Émirats arabes unis
om|Oman|عمان|
bh|Bahrain|البحرين|Bahreïn;Bahrein
ye|Yemen|اليمن|Yémen;Jemen
mr|Mauritania|موريتانيا|Mauritanie
so|Somalia|الصومال|
dj|Djibouti|جيبوتي|
km|Comoros|جزر القمر|Comores
ng|Nigeria|نيجيريا|Nigéria
gh|Ghana|غانا|
sn|Senegal|السنغال|Sénégal
cm|Cameroon|الكاميرون|Cameroun;Kamerun
ci|Ivory Coast|كوت ديفوار|Cote d'Ivoire;Côte d'Ivoire;Elfenbeinküste;ساحل العاج
za|South Africa|جنوب أفريقيا|Afrique du Sud;Südafrika;Sudafrica;جنوب افريقيا
ml|Mali|مالي|
bf|Burkina Faso|بوركينا فاسو|
cd|DR Congo|الكونغو الديمقراطية|Congo DR;Democratic Republic of the Congo;RD Congo;DR Congo
cg|Congo|الكونغو|Republic of the Congo
ao|Angola|أنغولا|
zm|Zambia|زامبيا|
zw|Zimbabwe|زيمبابوي|
ke|Kenya|كينيا|
ug|Uganda|أوغندا|
tz|Tanzania|تنزانيا|
et|Ethiopia|إثيوبيا|Éthiopie;Äthiopien
gn|Guinea|غينيا|Guinée
ga|Gabon|الغابون|
bj|Benin|بنين|Bénin
tg|Togo|توغو|
cv|Cape Verde|الرأس الأخضر|Cabo Verde;Cap-Vert;Kap Verde
mz|Mozambique|موزمبيق|Moçambique
mg|Madagascar|مدغشقر|
na|Namibia|ناميبيا|
bw|Botswana|بوتسوانا|
sl|Sierra Leone|سيراليون|
lr|Liberia|ليبيريا|
gm|Gambia|غامبيا|The Gambia
ne|Niger|النيجر|
rw|Rwanda|رواندا|
mw|Malawi|ملاوي|
gq|Equatorial Guinea|غينيا الاستوائية|Guinée équatoriale
cf|Central African Republic|أفريقيا الوسطى|Centrafrique
td|Chad|تشاد|Tchad
gw|Guinea-Bissau|غينيا بيساو|Guinea Bissau
bi|Burundi|بوروندي|
ls|Lesotho|ليسوتو|
sz|Eswatini|إسواتيني|Swaziland
mu|Mauritius|موريشيوس|
ss|South Sudan|جنوب السودان|
er|Eritrea|إريتريا|
ir|Iran|إيران|IR Iran;Iran Islamic Republic;Irán;איראן;ايران
jp|Japan|اليابان|Japon;Giappone;Japón;Япония;יפן
kr|South Korea|كوريا الجنوبية|Korea Republic;Korea Rep;Republic of Korea;Corée du Sud;Südkorea;Corea del Sur;Южная Корея;דרום קוריאה
kp|North Korea|كوريا الشمالية|Korea DPR;Corée du Nord;Nordkorea
cn|China|الصين|China PR;Chine;Cina;Китай;סין
in|India|الهند|Inde;Indien;הודו
au|Australia|أستراليا|Australie;Australien;אוסטרליה;استراليا
uz|Uzbekistan|أوزبكستان|Ouzbékistan;Usbekistan;Узбекистан;اوزبكستان
kg|Kyrgyzstan|قيرغيزستان|Kirghizistan;Kirgisistan
tj|Tajikistan|طاجيكستان|Tadjikistan;Tadschikistan
tm|Turkmenistan|تركمانستان|Turkménistan
th|Thailand|تايلاند|Thaïlande
vn|Vietnam|فيتنام|Viet Nam
id|Indonesia|إندونيسيا|Indonésie;اندونيسيا
my|Malaysia|ماليزيا|Malaisie
sg|Singapore|سنغافورة|Singapour
ph|Philippines|الفلبين|Philippinen
hk|Hong Kong|هونغ كونغ|
tw|Chinese Taipei|تايبيه الصينية|Taiwan
af|Afghanistan|أفغانستان|Afghanistan
pk|Pakistan|باكستان|
bd|Bangladesh|بنغلاديش|
np|Nepal|نيبال|
mm|Myanmar|ميانمار|Birmanie
kh|Cambodia|كمبوديا|Cambodge
la|Laos|لاوس|
mn|Mongolia|منغوليا|
lk|Sri Lanka|سريلانكا|
mv|Maldives|جزر المالديف|
nz|New Zealand|نيوزيلندا|Nouvelle-Zélande;Neuseeland
br|Brazil|البرازيل|Brasil;Brasilien;Brésil;Brasile;Бразилия;ברזיל
ar|Argentina|الأرجنتين|Argentine;Argentinien;Аргентина;ארגנטינה;الارجنتين
uy|Uruguay|أوروغواي|اوروغواي
co|Colombia|كولومبيا|Kolumbien
cl|Chile|تشيلي|Chili
pe|Peru|بيرو|Pérou
ec|Ecuador|الإكوادور|Équateur;الاكوادور
py|Paraguay|باراغواي|
bo|Bolivia|بوليفيا|Bolivie
ve|Venezuela|فنزويلا|
us|USA|الولايات المتحدة|United States;US;USMNT;United States of America;Estados Unidos;Vereinigte Staaten;Stati Uniti;États-Unis;ארצות הברית;أمريكا
ca|Canada|كندا|Kanada
mx|Mexico|المكسيك|México;Mexiko;Messico;Mexique;מקסיקו
cr|Costa Rica|كوستاريكا|
pa|Panama|بنما|Panamá
hn|Honduras|هندوراس|
jm|Jamaica|جامايكا|Jamaïque
sv|El Salvador|السلفادور|
gt|Guatemala|غواتيمالا|
ht|Haiti|هايتي|Haïti
cu|Cuba|كوبا|
tt|Trinidad and Tobago|ترينيداد وتوباغو|Trinidad & Tobago
cw|Curacao|كوراساو|Curaçao
sr|Suriname|سورينام|
ni|Nicaragua|نيكاراغوا|
do|Dominican Republic|الدومينيكان|República Dominicana
"""

    private val index: HashMap<String, NT> by lazy {
        val m = HashMap<String, NT>()
        for (line in DATA.trim().split('\n')) {
            val p = line.split('|')
            if (p.size < 3) continue
            val nt = NT(p[0].trim(), p[1].trim(), p[2].trim())
            m[key(nt.en)] = nt
            m[key(nt.ar)] = nt
            if (p.size > 3) for (a in p[3].split(';')) if (a.isNotBlank()) m[key(a)] = nt
        }
        m
    }

    fun find(name: String): NT? {
        var k = key(name)
        index[k]?.let { return it }
        // "Czech Republic national team", "منتخب مصر", "Egypt NT" ...
        for (w in arrayOf("nationalteam", "nationalfootballteam", "منتخب", "nt", "team", "fc")) {
            if (k.endsWith(w) && k.length > w.length + 2) { index[k.removeSuffix(w)]?.let { return it } }
            if (k.startsWith(w) && k.length > w.length + 2) { index[k.removePrefix(w)]?.let { return it } }
        }
        return null
    }
}
