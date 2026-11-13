package com.dk.home

import java.text.Normalizer
import java.util.Calendar

/**
 * Lệnh giọng nói đã hiểu được. Phần này KHÔNG phụ thuộc Android (chỉ dùng thư viện Java),
 * nên kiểm thử được độc lập: AiParser.parse("nhắn tin cho Nam nội dung tối nay họp") -> AiCmd.Sms(...)
 */
sealed class AiCmd {
    /** target = tên đọc được (hoặc số); number != "" nếu người nói đọc ra số điện thoại. */
    data class Call(val target: String, val number: String) : AiCmd()
    /** body rỗng = chỉ mở màn hình soạn tin tới người đó. */
    data class Sms(val target: String, val number: String, val body: String) : AiCmd()
    data class Camera(val selfie: Boolean, val shoot: Boolean) : AiCmd()
    object Gallery : AiCmd()
    /** query đã bỏ dấu; "" = phát một bài bất kỳ. */
    data class Music(val query: String) : AiCmd()
    /** id màn hình trong app: calllog, contacts, messages, music, clock, calendar, recorder, calc, snake, tv, remote, youtube, settings, wifi, apps, home */
    data class Open(val id: String) : AiCmd()
    data class OpenApp(val name: String) : AiCmd()
    /** Mở YouTube và tìm / phát bài này (query giữ nguyên dấu để gõ vào ô tìm). */
    data class YouTube(val query: String) : AiCmd()
    /** Mở app con TV và phát kênh/đài này (query giữ nguyên dấu; \"\" = chỉ mở trang chọn đài). */
    data class Tv(val query: String) : AiCmd()
    data class Volume(val up: Boolean) : AiCmd()
    data class Torch(val on: Boolean) : AiCmd()
    data class Bluetooth(val on: Boolean) : AiCmd()
    data class SetAlarm(val h: Int, val m: Int) : AiCmd()
    /** Xóa toàn bộ báo thức đã đặt ("xóa tất cả báo thức", "xóa hết báo thức"). */
    object ClearAlarms : AiCmd()
    object Lock : AiCmd()
    object Time : AiCmd()
    object Date : AiCmd()
    object Help : AiCmd()
    object Cancel : AiCmd()
    data class Unknown(val text: String) : AiCmd()
}

/** Bỏ dấu tiếng Việt + chữ thường. */
fun aiNorm(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('đ', 'd').replace('Đ', 'D')
        .lowercase()

object AiParser {
    private val UNITS = mapOf(
        "khong" to 0, "mot" to 1, "hai" to 2, "ba" to 3, "bon" to 4, "tu" to 4,
        "nam" to 5, "lam" to 5, "sau" to 6, "bay" to 7, "tam" to 8, "chin" to 9
    )
    private val SMS_NEXT = setOf("tin", "cho", "toi", "den", "sms")
    private val SMS_SKIP1 = setOf("tin", "nhan", "sms", "loi", "van", "ban")
    private val SMS_SKIP2 = setOf("cho", "toi", "den", "qua", "so", "dien", "thoai")
    private val CALL_SKIP = setOf("thoai", "lai", "cho", "toi", "den", "so", "cua", "may", "dt", "vao", "qua", "sdt")
    private val CALL_TAIL = setOf("di", "nhe", "nha", "giup", "minh", "toi", "ngay", "luon", "voi", "gium", "a", "ha", "nhanh", "dum", "cho")
    private val MUSIC_VERB = setOf("phat", "nghe", "bat", "mo", "choi")
    private val MUSIC_SKIP = setOf("nhac", "bai", "hat", "ca", "khuc", "cua", "ten", "la", "nghe", "di", "nhe", "giup", "toi", "minh", "cho")
    private val MUSIC_TAIL = setOf("di", "nhe", "nha", "giup", "toi", "minh", "ngay", "len", "cho", "nghe", "voi", "a")
    private val OPEN_VERB = setOf("mo", "vao", "chay", "khoi", "truy", "bat")
    private val OPEN_SKIP = setOf("dong", "cap", "ung", "dung", "app", "phan", "mem", "cho", "toi", "len", "di")

    /** (cụm từ khóa, id màn hình) — cụ thể đứng trước, chung chung đứng sau. */
    private val SECTIONS = listOf(
        listOf("nhat ky", "lich su cuoc goi", "cuoc goi nho", "cuoc goi gan day", "cuoc goi da goi") to "calllog",
        listOf("danh ba", "danh sach lien he", "lien he") to "contacts",
        listOf("tin nhan", "hop thu", "xem tin", "doc tin") to "messages",
        listOf("ghi am") to "recorder",
        listOf("may tinh", "tinh toan") to "calc",
        listOf("ran san moi", "tro choi", "game", "con ran") to "snake",
        listOf("dong ho", "bao thuc") to "clock",
        listOf("lich", "am lich", "lich thang") to "calendar",
        listOf("nhac", "may nghe nhac", "trinh phat nhac") to "music",
        listOf("youtube", "you tube", "du tup") to "youtube",
        listOf("truyen hinh") to "tv",
        listOf("cai dat") to "settings",
        listOf("wifi", "wi fi", "mang khong day") to "wifi"
    )

    private fun seqIndex(t: List<String>, p: List<String>, from: Int = 0): Int {
        if (p.isEmpty()) return -1
        var i = from
        while (i + p.size <= t.size) {
            var ok = true
            for (k in p.indices) if (t[i + k] != p[k]) { ok = false; break }
            if (ok) return i
            i++
        }
        return -1
    }

    /** Đọc số điện thoại: "0912 345 678", "không chín một hai ...", "cộng 84 ...". "" nếu không phải số (ít nhất 3 chữ số). */
    fun spokenNumber(t: List<String>): String {
        if (t.isEmpty()) return ""
        val sb = StringBuilder()
        var digits = 0
        for ((i, w) in t.withIndex()) {
            when {
                w.all { it.isDigit() || it == '+' } -> { sb.append(w); digits += w.count { it.isDigit() } }
                w == "cong" && i == 0 -> sb.append('+')
                w == "linh" || w == "le" -> { sb.append('0'); digits++ }
                UNITS.containsKey(w) -> { sb.append(UNITS[w]); digits++ }
                else -> return ""
            }
        }
        return if (digits >= 3) sb.toString() else ""
    }

    /** Đọc số 0..99 bằng chữ từ vị trí i: (giá trị, số từ đã dùng) hoặc null. */
    private fun wordNumber(t: List<String>, i: Int): Pair<Int, Int>? {
        val a = t.getOrNull(i) ?: return null
        if (a == "muoi") {
            val u = t.getOrNull(i + 1)?.let { UNITS[it] }
            return if (u != null && u in 1..9) (10 + u) to 2 else 10 to 1
        }
        val u1 = UNITS[a] ?: return null
        if (t.getOrNull(i + 1) == "muoi") {
            val u2 = t.getOrNull(i + 2)?.let { UNITS[it] }
            return if (u2 != null && u2 in 1..9) (u1 * 10 + u2) to 3 else (u1 * 10) to 2
        }
        return u1 to 1
    }

    /** Đổi các cụm số đọc bằng chữ thành chữ số ("sáu giờ ba mươi" -> "6 gio 30"). */
    private fun numTokens(n: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < n.size) {
            val w = n[i]
            // "sáu" trùng chữ "sau" (sau 20 phút): chỉ coi là số 6 khi đi liền "giờ/phút/mươi/rưỡi"
            val isSau = w == "sau" && n.getOrNull(i + 1) !in setOf("gio", "phut", "muoi", "ruoi", "h", "g") && i != n.lastIndex
            val r = if (isSau) null else wordNumber(n, i)
            if (r != null) { out.add(r.first.toString()); i += r.second } else { out.add(w); i++ }
        }
        return out
    }

    fun parse(text: String, now: Calendar = Calendar.getInstance()): AiCmd {
        val words = ArrayList<String>()   // từ gốc (giữ dấu) để hiển thị / làm nội dung tin
        val n = ArrayList<String>()       // từ đã bỏ dấu, bỏ dấu câu
        for (w in text.trim().split(Regex("\\s+"))) {
            val k = aiNorm(w).filter { it.isLetterOrDigit() || it == ':' || it == '+' }
            if (k.isNotEmpty()) { words.add(w.trim(',', '.', '!', '?', ';', '"', '\'')); n.add(k) }
        }
        if (n.isEmpty()) return AiCmd.Unknown(text)
        val joined = n.joinToString(" ")
        fun has(vararg ps: String) = ps.any { seqIndex(n, it.split(' ')) >= 0 }
        fun hasTok(vararg ts: String) = n.any { it in ts }

        if (joined in setOf("huy", "huy bo", "thoi", "thoi khong", "khong", "bo qua", "khong can", "thoat", "thoat ra")) return AiCmd.Cancel
        if (has("giup do", "huong dan", "lam duoc gi", "biet lam gi", "tro giup", "co the lam gi")) return AiCmd.Help

        // ---- Nhắn tin
        val smsAt = n.indices.firstOrNull { i ->
            val nx = n.getOrNull(i + 1)
            (n[i] == "nhan" && nx in SMS_NEXT) ||
                (n[i] == "gui" && (nx == "tin" || nx == "sms" || nx == "loi")) ||
                (n[i] == "soan" && nx == "tin")
        }
        if (smsAt != null) return parseSms(text, words, n, smsAt)

        // ---- Gọi điện ("cuộc gọi" là nhật ký, không phải lệnh gọi)
        val callAt = if (has("cuoc goi")) -1 else n.indexOfFirst { it == "goi" || it == "call" }
        if (callAt >= 0) {
            var i = callAt + 1
            while (i < n.size) {
                val nx = n.getOrNull(i + 1)
                val skip = if (n[i] == "dien") nx in CALL_SKIP else n[i] in CALL_SKIP   // "điện" đứng cuối = tên người (Điền)
                if (!skip) break
                i++
            }
            var e = n.size
            while (e > i && n[e - 1] in CALL_TAIL) e--
            if (e <= i) return AiCmd.Unknown(text)
            var number = spokenNumber(n.subList(i, e))
            var start = i
            if (number.isEmpty()) {
                // Còn sót từ thừa trước số ("gọi tới số máy bàn 094...") -> lấy đoạn cuối đọc được thành số
                for (s in i + 1 until e) {
                    val num = spokenNumber(n.subList(s, e))
                    if (num.count { it.isDigit() } >= 6) { number = num; start = s; break }
                }
            }
            return AiCmd.Call(words.subList(start, e).joinToString(" "), number)
        }

        // ---- Xóa toàn bộ báo thức: "xóa tất cả báo thức", "xóa hết báo thức", "hủy toàn bộ báo thức"
        if (has("bao thuc") && hasTok("xoa", "huy", "go", "clear") &&
            (has("tat ca", "toan bo", "het", "moi", "all") || hasTok("het", "all")))
            return AiCmd.ClearAlarms

        // ---- Báo thức
        if (has("bao thuc", "dat chuong", "danh thuc", "hen gio", "nhac toi")) return parseAlarm(n, now)

        // ---- Thư viện ảnh / máy ảnh
        if (has("thu vien", "album", "bo suu tap", "xem anh", "xem hinh", "mo anh", "mo hinh",
                "anh da chup", "anh vua chup", "xem lai anh")) return AiCmd.Gallery
        if (has("chup", "selfie", "tu suong", "may anh", "camera")) {
            val selfie = has("selfie", "tu suong", "camera truoc", "may anh truoc", "chup minh", "chup toi")
            val shoot = has("chup", "selfie", "tu suong")
            return AiCmd.Camera(selfie, shoot)
        }

        // ---- Điều khiển TV (remote qua WebRTC): "điều khiển tivi", "mở điều khiển TV", "remote tivi" -> màn hình remote (KHÔNG phải app TV xem kênh)
        val remoteAt = has("dieu khien") || has("remote")
        if (remoteAt) {
            val tvWord = n.any { it == "tv" || it == "tivi" || it == "ti" || it == "vi" || it == "tele" }
            val rest = n.filter { it !in TV_SKIP && it != "dieu" && it != "khien" && it != "remote" }
            if (tvWord || rest.isEmpty()) return AiCmd.Open("remote")
        }

        // ---- YouTube kèm tên bài / từ khóa: "mở YouTube mở bài Liễu Thanh Yên" -> tìm trên YouTube (không phải nhạc trong máy)
        val yt = n.indexOfFirst { it == "youtube" || it == "dutup" || it == "yutup" || it == "utube" || it == "yotube" }
            .let { if (it >= 0) it else seqIndex(n, listOf("you", "tube")) }
        if (yt >= 0) parseYoutube(words, n, yt)?.let { return it }

        // ---- TV: "mở TV", "xem kênh HTV9", "mở đài Cà Mau" -> app con TV (kèm tên kênh nếu có)
        val tvAt = n.indexOfFirst { it == "tv" || it == "tivi" }.let { a -> if (a >= 0) a else seqIndex(n, listOf("ti", "vi")) }
        val chAt = n.indices.firstOrNull { n[it] == "kenh" || n[it] == "dai" || (n[it] == "truyen" && n.getOrNull(it + 1) == "hinh") } ?: -1
        if (tvAt >= 0 || (chAt >= 0 && n.take(chAt).all { it in TV_SKIP })) {
            val q = n.indices.filter { n[it] !in TV_SKIP }.let { idx ->
                if (idx.isEmpty()) "" else words.slice(idx.first()..idx.last()).joinToString(" ")
            }
            return if (q.isEmpty()) AiCmd.Open("tv") else AiCmd.Tv(q)
        }
        // ---- Xem video / clip (không nói YouTube) -> tìm trên YouTube
        val vidAt = n.indexOfFirst { it == "video" || it == "clip" }
        if (vidAt >= 0 && n.any { it in setOf("xem", "mo", "tim", "phat", "bat") })
            parseYoutube(words, n, vidAt)?.let { return it }

        // ---- Phát nhạc
        val musicWord = n.indexOfFirst { it == "nhac" || it == "bai" || it == "khuc" || it == "hat" }
        val verbIdx = n.indexOfFirst { it in MUSIC_VERB }
        if (musicWord >= 0 && verbIdx >= 0) {
            var i = musicWord
            while (i < n.size && n[i] in MUSIC_SKIP) i++
            var e = n.size
            while (e > i && n[e - 1] in MUSIC_TAIL) e--
            val q = n.subList(i, e).joinToString(" ")
            if (q.isEmpty() && n[verbIdx] == "mo") return AiCmd.Open("music")
            return AiCmd.Music(q)
        }

        // ---- Âm lượng
        if (has("am luong", "tieng", "volume", "am thanh") || has("to len", "lon len", "nho di", "nho lai", "to hon", "nho hon")) {
            val up = hasTok("tang", "to", "lon", "len", "cao")
            val down = hasTok("giam", "nho", "be", "xuong", "thap")
            if (up != down) return AiCmd.Volume(up)
        }

        // ---- Đèn pin
        if (has("den pin", "den flash", "den led", "den chieu") || (hasTok("den") && hasTok("bat", "mo", "tat") && !hasTok("nhac")))
            return AiCmd.Torch(!hasTok("tat"))

        // ---- Bluetooth
        if (hasTok("bluetooth", "blutooth", "blutut")) return AiCmd.Bluetooth(!hasTok("tat"))

        // ---- Khóa máy
        if (has("khoa man hinh", "khoa may", "khoa lai", "khoa dien thoai", "tat man hinh")) return AiCmd.Lock

        // ---- Hỏi giờ / ngày
        if (has("may gio", "gio hien tai", "bay gio la")) return AiCmd.Time
        if (has("ngay may", "ngay bao nhieu", "thu may", "hom nay la", "ngay thang", "ngay hom nay")) return AiCmd.Date

        // ---- Mở màn hình trong app
        for ((keys, id) in SECTIONS) if (has(*keys.toTypedArray())) return AiCmd.Open(id)

        // ---- Mở ứng dụng khác theo tên
        val vi = n.indexOfFirst { it in OPEN_VERB }
        if (vi >= 0) {
            var i = vi + 1
            while (i < n.size && n[i] in OPEN_SKIP) i++
            var e = n.size
            while (e > i && n[e - 1] in OPEN_SKIP) e--
            if (e > i) return AiCmd.OpenApp(n.subList(i, e).joinToString(" "))
        }
        if (has("man hinh chinh", "trang chu", "ve nha", "man hinh cho")) return AiCmd.Open("home")
        return AiCmd.Unknown(text)
    }

    private val TV_SKIP = setOf("mo", "bat", "xem", "vao", "chuyen", "sang", "kenh", "dai", "truyen", "hinh", "cho", "toi", "len", "di", "nhe",
        "giup", "tv", "tivi", "ti", "vi", "ung", "dung", "app", "nha", "voi", "ngay")
    private val YT_SKIP = setOf("mo", "phat", "nghe", "bat", "tim", "kiem", "vao", "xem", "bai", "nhac", "khuc", "video",
        "clip", "cua", "ten", "di", "nhe", "giup", "len", "tren")
    private val YT_TAIL = setOf("di", "nhe", "nha", "giup", "len", "ngay", "luon", "voi", "a", "tren", "o")

    /** Từ khóa tìm kiếm trên YouTube, ưu tiên phần SAU chữ YouTube; không có thì lấy phần trước ("mở bài X trên YouTube"). */
    private fun parseYoutube(words: List<String>, n: List<String>, yt: Int): AiCmd? {
        val ytLen = if (n[yt] == "you") 2 else 1
        fun span(from: Int, to: Int): Pair<Int, Int> {
            var i = from
            while (i < to) {
                val two = if (i + 1 < to) n[i] + " " + n[i + 1] else ""
                i += when {
                    two == "bai hat" || two == "ca khuc" || two == "cho toi" || two == "tim kiem" -> 2
                    n[i] in YT_SKIP -> 1
                    else -> break
                }
            }
            var e = to
            while (e > i && n[e - 1] in YT_TAIL) e--
            return i to e
        }
        var (a, b) = span(yt + ytLen, n.size)
        if (b <= a) { val r = span(0, yt); a = r.first; b = r.second }
        if (b <= a) return null     // chỉ nói "mở YouTube" -> mở app như thường
        return AiCmd.YouTube(words.subList(a, b).joinToString(" "))
    }

    private fun parseSms(text: String, words: List<String>, n: List<String>, at: Int): AiCmd {
        var i = at + 1
        while (i < n.size && n[i] in SMS_SKIP1) i++
        while (i < n.size && n[i] in SMS_SKIP2) i++
        if (i >= n.size) return AiCmd.Unknown(text)

        // Tìm chỗ bắt đầu nội dung: ưu tiên cụm rõ ràng ("nội dung", "như sau"), sau đó mới tới "là"/"rằng".
        var mStart = -1; var mLen = 0
        for (j in i + 1 until n.size) {
            val m = when {
                n[j] == "noi" && n.getOrNull(j + 1) == "dung" -> 2
                n[j] == "voi" && n.getOrNull(j + 1) == "noi" && n.getOrNull(j + 2) == "dung" -> 3
                n[j] == "nhu" && n.getOrNull(j + 1) == "sau" -> 2
                else -> 0
            }
            if (m > 0) { mStart = j; mLen = m; break }
        }
        if (mStart < 0) for (j in i + 1 until n.size) {
            val m = when {
                n[j] == "noi" && n.getOrNull(j + 1) in setOf("la", "rang") -> 2
                n[j] == "rang" || n[j] == "la" -> 1
                else -> 0
            }
            if (m > 0) { mStart = j; mLen = m; break }
        }
        val tEnd = if (mStart < 0) n.size else mStart
        val target = words.subList(i, tEnd).joinToString(" ")
        val number = spokenNumber(n.subList(i, tEnd))
        var body = ""
        if (mStart >= 0) {
            var b = mStart + mLen
            if (n.getOrNull(b) in setOf("la", "rang", "sau")) b++
            if (b < words.size) body = words.subList(b, words.size).joinToString(" ")
        }
        body = body.replaceFirstChar { it.uppercase() }
        return AiCmd.Sms(target, number, body)
    }

    private fun parseAlarm(n: List<String>, now: Calendar): AiCmd {
        val a = numTokens(n).joinToString(" ")
        // Tương đối: "sau 20 phút", "sau 2 tiếng", "sau 1 giờ 15 phút"
        Regex("sau (\\d+) (gio|tieng|phut)(?: (\\d+) phut)?").find(a)?.let { m ->
            val v = m.groupValues[1].toInt()
            val minutes = if (m.groupValues[2] == "phut") v else v * 60 + (m.groupValues[3].toIntOrNull() ?: 0)
            val t = (now.clone() as Calendar).apply { add(Calendar.MINUTE, minutes) }
            return AiCmd.SetAlarm(t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE))
        }
        val m = Regex("(\\d{1,2})\\s*(?:gio|h|g|:)\\s*(\\d{1,2}|ruoi)?").find(a)
            ?: Regex("\\b(\\d{1,2})\\b").find(a)
            ?: return AiCmd.Open("clock")
        var h = m.groupValues[1].toInt()
        val mm = m.groupValues.getOrElse(2) { "" }
        var min = if (mm == "ruoi") 30 else mm.toIntOrNull() ?: 0
        // "bảy giờ kém mười lăm" = 6:45
        Regex("kem (\\d{1,2})").find(a)?.let { k ->
            val d = k.groupValues[1].toInt()
            if (d in 1..59) { min = 60 - d; h -= 1 }
        }
        val afternoon = Regex("\\b(chieu|toi)\\b").containsMatchIn(a)
        val night = Regex("\\bdem\\b").containsMatchIn(a)
        val noon = Regex("\\btrua\\b").containsMatchIn(a)
        if (afternoon && h in 1..11) h += 12
        if (night) { if (h == 12) h = 0 else if (h in 5..11) h += 12 }
        if (noon && h in 1..4) h += 12
        if (h < 0) h += 24
        if (h !in 0..23 || min !in 0..59) return AiCmd.Open("clock")
        return AiCmd.SetAlarm(h, min)
    }
}

/** Xếp hạng độ khớp tên (danh bạ, bài hát, ứng dụng) — chịu được lỗi nhận dạng nhẹ. */
object AiMatch {
    private val HONOR = setOf("anh", "chi", "em", "co", "chu", "bac", "ong", "thay", "cau")

    /** keys đã bỏ dấu + chữ thường. Trả về chỉ số các mục khớp, tốt nhất đứng đầu. */
    fun rank(keys: List<String>, query: String): List<Int> {
        val q = aiNorm(query).filter { it.isLetterOrDigit() || it == ' ' || it == '+' }.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return emptyList()
        val qt = q.split(' ')
        val qt2 = qt.filter { it !in HONOR }.ifEmpty { qt }
        val scored = ArrayList<Pair<Int, Int>>()
        for ((idx, key) in keys.withIndex()) {
            var s = score(key, qt)
            if (qt2 != qt) s = maxOf(s, score(key, qt2) - 10)
            if (s > 0) scored.add(idx to s)
        }
        return scored.sortedWith(compareBy({ -it.second }, { keys[it.first].length })).map { it.first }
    }

    private fun score(key: String, qt: List<String>): Int {
        val kt = key.split(' ', '-', '.', '_', ',').filter { it.isNotEmpty() }
        val jq = qt.joinToString(" ")
        if (key == jq) return 100
        if (key.startsWith(jq)) return 85
        if (qt.all { it in kt }) return 75 - (kt.size - qt.size).coerceIn(0, 10)
        if (key.contains(jq)) return 65
        if (qt.all { t -> kt.any { it.startsWith(t) } }) return 60
        if (qt.all { key.contains(it) }) return 50
        val hit = qt.count { t -> kt.any { it == t || it.startsWith(t) } }
        if (hit > 0 && hit * 2 >= qt.size) return 30 + hit
        // Lỗi nhận dạng nhẹ: sai 1-2 chữ cái
        if (jq.length >= 3) {
            val tol = if (jq.length >= 7) 2 else 1
            if (kt.any { lev(it, jq) <= tol } || lev(key, jq) <= tol) return 20
        }
        return 0
    }

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
