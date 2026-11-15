package com.sumcare.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 미세먼지 등급 (환경부 통합 기준, PM2.5 µg/m³) */
enum class AirGrade(val label: String, val color: Int, val isBad: Boolean) {
    GOOD("좋음", 0xFF3B82F6.toInt(), false),
    NORMAL("보통", 0xFF22C55E.toInt(), false),
    BAD("나쁨", 0xFFF59E0B.toInt(), true),
    VERY_BAD("매우나쁨", 0xFFEF4444.toInt(), true);

    companion object {
        fun fromPm25(v: Int) = when {
            v <= 15 -> GOOD
            v <= 35 -> NORMAL
            v <= 75 -> BAD
            else -> VERY_BAD
        }
    }
}

data class AirReading(
    val place: String,
    val pm10: Int,
    val pm25: Int,
    val source: String,
) {
    val grade get() = AirGrade.fromPm25(pm25)
}

/** 바깥 공기 데이터 공급원 */
interface AirSource {
    fun fetch(place: String): AirReading
}

/**
 * 에어코리아 대기오염정보 API (공공데이터포털)
 * 측정소별 실시간 측정정보 조회: getMsrstnAcctoRltmMesureDnsty
 */
class AirKoreaSource(private val serviceKey: String) : AirSource {
    override fun fetch(place: String): AirReading {
        val station = nearestStation(place)
        val url = "https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty" +
            "?serviceKey=$serviceKey&returnType=json&numOfRows=1&pageNo=1&dataTerm=DAILY&ver=1.3" +
            "&stationName=" + URLEncoder.encode(station, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val item = JSONObject(body).getJSONObject("response").getJSONObject("body")
            .getJSONArray("items").getJSONObject(0)
        return AirReading(
            place = place,
            pm10 = item.optString("pm10Value").toIntOrNull() ?: 0,
            pm25 = item.optString("pm25Value").toIntOrNull() ?: 0,
            source = "에어코리아 실시간 · $station 측정소",
        )
    }

    /** 간단 버전: 주소에 포함된 구 이름으로 측정소를 고른다 (실서비스에선 근접측정소 API 사용) */
    private fun nearestStation(place: String) = when {
        "분당" in place || "판교" in place -> "운중동"
        "강남" in place -> "강남구"
        "마포" in place -> "마포구"
        else -> "중구"
    }
}

/** 시연용 데모 공급원: 값을 직접 지정한다 */
class DemoAirSource : AirSource {
    var pm25 = 12
    override fun fetch(place: String) =
        AirReading(place, pm10 = (pm25 * 1.7).toInt(), pm25 = pm25, source = "데모 모드 (시연용 값)")
}
