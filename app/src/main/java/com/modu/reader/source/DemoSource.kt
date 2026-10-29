package com.modu.reader.source

import com.modu.reader.data.Book
import com.modu.reader.data.Category
import com.modu.reader.data.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 内置示例书源：公版古典名著节选，纯粹用来演示书架 / 分类 / 阅读器。
 * 不联网，删不掉，也不涉及任何版权内容。
 */
class DemoSource(override val cfg: SourceConfig) : BookSource {

    private data class DemoBook(
        val key: String,
        val title: String,
        val author: String,
        val cat: String,
        val intro: String,
        val chapters: List<Pair<String, String>>
    )

    override suspend fun categories(): List<Category> = cfg.categories

    override suspend fun list(category: Category, page: Int): List<Book> =
        withContext(Dispatchers.IO) {
            delay(120)
            if (page > 1) return@withContext emptyList()
            CATALOG.filter { it.cat == category.name }.map { it.toBook() }
        }

    override suspend fun search(key: String, page: Int): List<Book> =
        withContext(Dispatchers.IO) {
            delay(120)
            if (page > 1) return@withContext emptyList()
            CATALOG.filter {
                it.title.contains(key, true) || it.author.contains(key, true)
            }.map { it.toBook() }
        }

    override suspend fun detail(book: Book): Book =
        CATALOG.firstOrNull { it.key == book.url }?.toBook() ?: book

    override suspend fun chapters(book: Book): List<Chapter> {
        val b = CATALOG.firstOrNull { it.key == book.url } ?: return emptyList()
        return b.chapters.mapIndexed { i, c -> Chapter(i, c.first, "${book.url}#$i") }
    }

    override suspend fun content(book: Book, chapter: Chapter): String {
        val b = CATALOG.firstOrNull { it.key == book.url }
            ?: throw IllegalStateException("示例书不存在")
        val c = b.chapters.getOrNull(chapter.index)
            ?: throw IllegalStateException("章节不存在")
        return c.second.trimIndent()
    }

    private fun DemoBook.toBook() = Book(
        sourceId = ID,
        url = key,
        title = title,
        author = author,
        cover = "",
        intro = intro,
        status = "已完结",
        latestChapter = chapters.lastOrNull()?.first ?: ""
    )

    companion object {

        const val ID = "demo"

        fun config() = SourceConfig(
            id = ID,
            name = "示例书库",
            baseUrl = "",
            builtin = true,
            note = "内置公版古典名著节选，用于演示书架、分类与阅读器。不可删除。",
            categories = listOf(
                Category("古典小说", "demo://novel"),
                Category("诗词文选", "demo://poem"),
                Category("笔记杂谈", "demo://notes")
            )
        )

        private val CATALOG = listOf(
            DemoBook(
                key = "demo://book/xiyouji",
                title = "西游记",
                author = "吴承恩",
                cat = "古典小说",
                intro = "中国古代第一部浪漫主义章回体长篇神魔小说，讲述唐僧师徒四人西行取经的故事。此处为公版节选。",
                chapters = listOf(
                    "第一回 灵根育孕源流出 心性修持大道生" to """
                        诗曰：混沌未分天地乱，茫茫渺渺无人见。自从盘古破鸿蒙，开辟从兹清浊辨。

                        覆载群生仰至仁，发明万物皆成善。欲知造化会元功，须看西游释厄传。

                        盖闻天地之数，有十二万九千六百岁为一元。将一元分为十二会，乃子、丑、寅、卯、辰、巳、午、未、申、酉、戌、亥之十二支也。每会该一万八百岁。

                        海外有一国土，名曰傲来国。国近大海，海中有一座名山，唤为花果山。那座山正当顶上，有一块仙石。其石有三丈六尺五寸高，有二丈四尺围圆。

                        盖自开辟以来，每受天真地秀，日精月华，感之既久，遂有灵通之意。内育仙胞，一日迸裂，产一石卵，似圆球样大。因见风，化作一个石猴。
                    """.trimIndent(),
                    "第二回 悟彻菩提真妙理 断魔归本合元神" to """
                        话表美猴王得了姓名，怡然踊跃，对菩提前作礼启谢。那祖师即命大众引孙悟空出二门外，教他洒扫应对、进退周旋之节。

                        一日，祖师登坛高坐，唤集诸仙，开讲大道。真个是：天花乱坠，地涌金莲。妙演三乘教，精微万法全。

                        祖师道：“你今有缘，我亦喜说。我门中有十二个字，分派起名，到你乃第十辈之小徒矣。”猴王道：“那十二个字？”祖师道：“乃广、大、智、慧、真、如、性、海、颖、悟、圆、觉十二字。”

                        猴王笑道：“好！好！好！自今就叫做孙悟空也！”
                    """.trimIndent()
                )
            ),
            DemoBook(
                key = "demo://book/hongloumeng",
                title = "红楼梦",
                author = "曹雪芹",
                cat = "古典小说",
                intro = "中国古典小说巅峰之作，以贾府兴衰为背景，写尽人情世态。此处为公版节选。",
                chapters = listOf(
                    "第一回 甄士隐梦幻识通灵 贾雨村风尘怀闺秀" to """
                        此开卷第一回也。作者自云：因曾历过一番梦幻之后，故将真事隐去，而借“通灵”之说，撰此《石头记》一书也。

                        满纸荒唐言，一把辛酸泪。都云作者痴，谁解其中味？

                        原来女娲氏炼石补天之时，于大荒山无稽崖炼成高经十二丈、方经二十四丈顽石三万六千五百零一块。娲皇氏只用了三万六千五百块，只单单剩了一块未用，便弃在此山青埂峰下。

                        谁知此石自经煅炼之后，灵性已通，因见众石俱得补天，独自己无材不堪入选，遂自怨自叹，日夜悲号惭愧。
                    """.trimIndent(),
                    "第二回 贾夫人仙逝扬州城 冷子兴演说荣国府" to """
                        诗云：一局输赢料不真，香销茶尽尚逡巡。欲知目下兴衰兆，须问旁观冷眼人。

                        却说封肃因听见公差传唤，忙出来陪笑启问。那些人只嚷：“快请出甄爷来！”封肃忙陪笑道：“小人姓封，并不姓甄。”

                        雨村道：“方才说这政公，已有衔玉之儿，又有长子所遗一个弱孙。这赦老竟无一个不成？”子兴道：“政公既有玉儿之后，其妾又生了一个，倒不知其好歹。”
                    """.trimIndent()
                )
            ),
            DemoBook(
                key = "demo://book/sanguo",
                title = "三国演义",
                author = "罗贯中",
                cat = "古典小说",
                intro = "章回体历史演义小说开山之作，写东汉末年至西晋初年的百年风云。此处为公版节选。",
                chapters = listOf(
                    "第一回 宴桃园豪杰三结义 斩黄巾英雄首立功" to """
                        滚滚长江东逝水，浪花淘尽英雄。是非成败转头空。青山依旧在，几度夕阳红。

                        白发渔樵江渚上，惯看秋月春风。一壶浊酒喜相逢。古今多少事，都付笑谈中。

                        话说天下大势，分久必合，合久必分。周末七国分争，并入于秦。及秦灭之后，楚、汉分争，又并入于汉。

                        汉朝自高祖斩白蛇而起义，一统天下，后来光武中兴，传至献帝，遂分为三国。
                    """.trimIndent(),
                    "第二回 张翼德怒鞭督邮 何国舅谋诛宦竖" to """
                        且说董卓字仲颖，陇西临洮人也，官拜河东太守，自来骄傲。当日怠慢了玄德，张飞性发，便欲杀之。

                        玄德与关公急止之曰：“他是朝廷命官，岂可擅杀？”飞曰：“若不杀这厮，反要在他部下听令，其实不甘！二兄要便住在此，我自投别处去也！”

                        玄德曰：“我三人义同生死，岂可相离？不若都投别处去便了。”飞曰：“若如此，稍解吾恨。”
                    """.trimIndent()
                )
            ),
            DemoBook(
                key = "demo://book/shuihu",
                title = "水浒传",
                author = "施耐庵",
                cat = "古典小说",
                intro = "中国第一部以农民起义为题材的长篇章回小说。此处为公版节选。",
                chapters = listOf(
                    "楔子 张天师祈禳瘟疫 洪太尉误走妖魔" to """
                        试看书林隐处，几多俊逸儒流。虚名薄利不关愁，裁冰及剪雪，谈笑看吴钩。

                        评议前王并后帝，分真伪占据中州，七雄扰扰乱春秋。兴亡如脆柳，身世类虚舟。

                        见成名无数，图名无数，更有那逃名无数。霎时新月下长川，沧海变桑田古路。
                    """.trimIndent(),
                    "第一回 王教头私走延安府 九纹龙大闹史家村" to """
                        话说故宋哲宗皇帝在时，其时去仁宗天子已远，东京开封府汴梁宣武军，便有一个浮浪破落户子弟，姓高，排行第二，自小不成家业，只好刺枪使棒，最是踢得好脚气毬。

                        京师人口顺，不叫高二，却都叫他做高毬。后来发迹，便将气毬那字去了毛傍，添作立人，便改作姓高名俅。
                    """.trimIndent()
                )
            ),
            DemoBook(
                key = "demo://book/tangshi",
                title = "唐诗选",
                author = "多人",
                cat = "诗词文选",
                intro = "唐代诗歌精选，全部为公版作品。",
                chapters = listOf(
                    "春江花月夜 · 张若虚" to """
                        春江潮水连海平，海上明月共潮生。
                        滟滟随波千万里，何处春江无月明！

                        江流宛转绕芳甸，月照花林皆似霰。
                        空里流霜不觉飞，汀上白沙看不见。

                        江天一色无纤尘，皎皎空中孤月轮。
                        江畔何人初见月？江月何年初照人？
                    """.trimIndent(),
                    "将进酒 · 李白" to """
                        君不见，黄河之水天上来，奔流到海不复回。
                        君不见，高堂明镜悲白发，朝如青丝暮成雪。

                        人生得意须尽欢，莫使金樽空对月。
                        天生我材必有用，千金散尽还复来。

                        烹羊宰牛且为乐，会须一饮三百杯。
                    """.trimIndent()
                )
            ),
            DemoBook(
                key = "demo://book/guwen",
                title = "古文观止（选）",
                author = "吴楚材 编",
                cat = "诗词文选",
                intro = "历代散文名篇选本，此处取两篇短制。",
                chapters = listOf(
                    "陋室铭 · 刘禹锡" to """
                        山不在高，有仙则名。水不在深，有龙则灵。斯是陋室，惟吾德馨。

                        苔痕上阶绿，草色入帘青。谈笑有鸿儒，往来无白丁。可以调素琴，阅金经。

                        无丝竹之乱耳，无案牍之劳形。南阳诸葛庐，西蜀子云亭。孔子云：何陋之有？
                    """.trimIndent(),
                    "爱莲说 · 周敦颐" to """
                        水陆草木之花，可爱者甚蕃。晋陶渊明独爱菊。自李唐来，世人甚爱牡丹。

                        予独爱莲之出淤泥而不染，濯清涟而不妖，中通外直，不蔓不枝，香远益清，亭亭净植，可远观而不可亵玩焉。

                        予谓菊，花之隐逸者也；牡丹，花之富贵者也；莲，花之君子者也。
                    """.trimIndent()
                )
            ),
            DemoBook(
                key = "demo://book/liaozhai",
                title = "聊斋志异（选）",
                author = "蒲松龄",
                cat = "笔记杂谈",
                intro = "清代文言短篇小说集，写狐鬼花妖，寄托人间冷暖。",
                chapters = listOf(
                    "考城隍" to """
                        予姊丈之祖，宋公讳焘，邑廪生。一日，病卧，见吏人持牒，牵白颠马来，云：“请赴试。”公言：“文宗未临，何遽得考？”

                        吏不言，但敦促之。公力疾乘马从去。路甚生疏。至一城郭，如王者都。移时入府廨，宫室壮丽。

                        上坐十馀官，都不知何人，惟关壮缪可识。檐下设几、墩各二，先有一秀才坐其末，公便与连肩。
                    """.trimIndent(),
                    "种梨" to """
                        有乡人货梨于市，颇甘芳，价腾贵。有道士破巾絮衣，丐于车前。乡人咄之，亦不去；乡人怒，加以叱骂。

                        道士曰：“一车数百颗，老衲止丐其一，于居士亦无大损，何怒为？”观者劝置劣者一枚令去，乡人执不肯。

                        肆中佣保者，见喋聒不堪，遂出钱市一枚，付道士。道士拜谢。
                    """.trimIndent()
                )
            ),
            DemoBook(
                key = "demo://book/lunyu",
                title = "论语（选）",
                author = "孔子弟子",
                cat = "笔记杂谈",
                intro = "儒家经典语录体著作，此处取《学而》《为政》两篇节选。",
                chapters = listOf(
                    "学而篇（节选）" to """
                        子曰：“学而时习之，不亦说乎？有朋自远方来，不亦乐乎？人不知而不愠，不亦君子乎？”

                        曾子曰：“吾日三省吾身：为人谋而不忠乎？与朋友交而不信乎？传不习乎？”

                        子曰：“不患人之不己知，患不知人也。”
                    """.trimIndent(),
                    "为政篇（节选）" to """
                        子曰：“为政以德，譬如北辰，居其所而众星共之。”

                        子曰：“吾十有五而志于学，三十而立，四十而不惑，五十而知天命，六十而耳顺，七十而从心所欲，不逾矩。”

                        子曰：“温故而知新，可以为师矣。”
                    """.trimIndent()
                )
            )
        )
    }
}
