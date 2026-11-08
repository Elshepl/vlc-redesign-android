package org.videolan.vlc.util

/**
 * Saved answers of the subtitle services of wave 2, for the parsers' tests. The answers with results are written
 * from the fields Bazarr's providers read (custom_libs/subliminal_patch/providers: titlovi.py, assrt.py, jimaku.py,
 * betaseries.py, subsro.py, napisy24.py, legendasnet.py); the error answers are what each service answered on
 * 2026-11-08 without a key or an account (no real one exists here yet).
 */
object SubtitleFixtures2 {

    // Titlovi: POST /gettoken, GET /search
    const val TITLOVI_TOKEN = """{"Token":"a1b2c3","UserId":4242,"ExpirationDate":"2026-11-15T12:00:00"}"""
    const val TITLOVI_FILM = """{"SubtitleResults":[
        {"Id":1,"Title":"The Matrix","Lang":"Hrvatski","Link":"https://titlovi.com/download/?type=1&mediaid=1","Release":"The.Matrix.1999.1080p.BluRay",
         "Season":-1,"Episode":-1,"Year":1999,"Rating":4.5,"DownloadCount":1200},
        {"Id":2,"Title":"The Matrix","Lang":"Srpski","Link":"https://titlovi.com/download/?type=1&mediaid=2","Release":"The.Matrix.1999.720p",
         "Season":-1,"Episode":-1,"Year":1999,"Rating":3,"DownloadCount":40},
        {"Id":3,"Title":"The Matrix","Lang":"Cirilica","Link":"http://titlovi.com/download/?type=1&mediaid=3","Release":"",
         "Season":-1,"Episode":-1,"Year":1999},
        {"Id":4,"Title":"The Matrix","Lang":"Deutsch","Link":"https://titlovi.com/download/?type=1&mediaid=4","Release":"x"},
        {"Id":5,"Title":"The Matrix","Lang":"Hrvatski","Link":"javascript:alert(1)","Release":"x"}],"PagesAvailable":1,"CurrentPage":1}"""
    const val TITLOVI_EPISODES = """{"SubtitleResults":[
        {"Id":11,"Title":"Breaking Bad","Lang":"Hrvatski","Link":"https://titlovi.com/download/?type=1&mediaid=11","Release":"Breaking.Bad.S01E02.720p","Season":1,"Episode":2},
        {"Id":12,"Title":"Breaking Bad","Lang":"Hrvatski","Link":"https://titlovi.com/download/?type=1&mediaid=12","Release":"Breaking.Bad.S01E03.720p","Season":1,"Episode":3},
        {"Id":13,"Title":"Breaking Bad","Lang":"Hrvatski","Link":"https://titlovi.com/download/?type=1&mediaid=13","Release":"Breaking.Bad.S01.Complete","Season":1,"Episode":0},
        {"Id":14,"Title":"Breaking Bad","Lang":"Hrvatski","Link":"https://titlovi.com/download/?type=1&mediaid=14","Release":"Breaking.Bad.S02E02","Season":2,"Episode":2}],"PagesAvailable":1}"""

    // Assrt: /sub/search, /sub/detail, /user/quota; 2026-11-08, live: a wrong token
    const val ASSRT_WRONG_TOKEN = """{"errmsg":"invalid token","status":20001}"""
    const val ASSRT_QUOTA = """{"user":{"action":"quota","result":"succeed","quota":20},"status":0}"""
    const val ASSRT_SEARCH = """{"status":0,"sub":{"action":"search","keyword":"The Matrix 1999","result":"succeed","subs":[
        {"id":602333,"videoname":"The.Matrix.1999.1080p.BluRay","native_name":"黑客帝国","lang":{"desc":"简 英","langlist":{"langchs":true,"langeng":true}},"vote_score":8},
        {"id":602334,"videoname":"不知道","native_name":["黑客帝國"],"lang":{"desc":"繁","langlist":{"langcht":true}}},
        {"id":602335,"videoname":"The.Matrix.1999.eng","lang":{"desc":"英","langlist":{"langeng":true}}},
        {"id":602336,"videoname":"no language"}]}}"""
    const val ASSRT_DETAIL = """{"status":0,"sub":{"action":"detail","result":"succeed","subs":[{"id":602333,"filelist":[
        {"f":"Show.S01E01.chs.srt","s":"40KB","url":"https://file.assrt.net/onthefly/1/a.chs.srt"},
        {"f":"Show.S01E02.eng.srt","s":"40KB","url":"https://file.assrt.net/onthefly/1/b.eng.srt"},
        {"f":"Show.S01E02.chs.srt","s":"40KB","url":"https://file.assrt.net/onthefly/1/b.chs.srt"}],
        "url":"https://file.assrt.net/download/602333/x.zip"}]}}"""
    const val ASSRT_DETAIL_ARCHIVE = """{"status":0,"sub":{"subs":[{"id":602333,"filelist":[],"url":"https://file.assrt.net/download/602333/x.zip"}]}}"""

    // Jimaku: /entries/search, /entries/{id}/files; 2026-11-08, live: a wrong key
    const val JIMAKU_UNAUTHORIZED = """{"error":"unauthorized","code":7}"""
    const val JIMAKU_ENTRIES = """[
        {"id":1,"name":"Sousou no Frieren","english_name":"Frieren: Beyond Journey's End","japanese_name":"葬送のフリーレン","anilist_id":154587,
         "flags":{"anime":true,"movie":false,"adult":false,"unverified":false}},
        {"id":2,"name":"Frieren Movie","english_name":"Frieren the movie","flags":{"anime":true,"movie":true}}]"""
    const val JIMAKU_FILES = """[
        {"url":"https://jimaku.cc/entry/1/download/%5BSubsPlease%5D%20Sousou%20no%20Frieren%20-%2002.ja.srt","name":"[SubsPlease] Sousou no Frieren - 02.ja.srt","size":45000,"last_modified":"2023-10-06T10:00:00Z"},
        {"url":"https://jimaku.cc/entry/1/download/all.7z","name":"all.7z","size":999999},
        {"url":"https://jimaku.cc/entry/1/download/tiny.srt","name":"tiny.srt","size":100},
        {"url":"https://jimaku.cc/entry/1/download/w.srt","name":"Frieren 02 [WhisperAI].srt","size":40000}]"""

    // BetaSeries: /shows/display, /shows/episodes; 2026-11-08, live: a wrong key
    const val BETASERIES_WRONG_KEY = """{
    "errors": [
        {
            "code": 1001,
            "text": "Mauvaise clé API."
        }
    ]
}"""
    const val BETASERIES_SHOW = """{"show":{"id":481,"thetvdb_id":81189,"imdb_id":"tt0903747","title":"Breaking Bad"},"errors":[]}"""
    const val BETASERIES_EPISODE = """{"episodes":[{"id":1,"code":"S01E02","subtitles":[
        {"id":11,"language":"VF","source":"addic7ed","quality":3,"file":"Breaking.Bad.S01E02.fr.srt","url":"https://www.betaseries.com/srt/11","date":"2008-01-28"},
        {"id":12,"language":"VO","source":"tvsubtitles","quality":2,"file":"Breaking.Bad.S01E02.en.srt","url":"https://www.betaseries.com/srt/12"},
        {"id":13,"language":"VF","source":"seriessub","quality":5,"file":"x.srt","url":"https://www.betaseries.com/srt/13"},
        {"id":14,"language":"VOVF","source":"addic7ed","file":"y.srt","url":"https://www.betaseries.com/srt/14"}]}],"errors":[]}"""

    // Subs.ro: /search/imdbid/{id}; 2026-11-08, live: a wrong key
    const val SUBSRO_WRONG_KEY = """{"message":"Invalid API key","status":403}"""
    const val SUBSRO_FILM = """{"status":200,"count":2,"items":[
        {"id":"abc1","title":"The Matrix","year":1999,"description":"The.Matrix.1999.1080p.BluRay","downloadLink":"https://subs.ro/subtitrare/descarca/abc1","language":"ro"},
        {"id":"abc2","title":"The Matrix","year":1999,"description":"x","downloadLink":"ftp://subs.ro/x"}]}"""
    const val SUBSRO_SERIES = """{"status":200,"items":[
        {"id":"s1","title":"Breaking Bad - Sezonul 1","description":"Breaking.Bad.S01E02.720p","downloadLink":"https://subs.ro/subtitrare/descarca/s1"},
        {"id":"s2","title":"Breaking Bad - Sezonul 2","description":"Breaking.Bad.S02E02","downloadLink":"https://subs.ro/subtitrare/descarca/s2"},
        {"id":"s3","title":"Breaking Bad - Sezonul 1","description":"Breaking.Bad.S01E03","downloadLink":"https://subs.ro/subtitrare/descarca/s3"},
        {"id":"s4","title":"Breaking Bad - Sezonul 1","description":"Sezonul 1 complet","downloadLink":"https://subs.ro/subtitrare/descarca/s4"}]}"""

    // Napisy24: POST /run/CheckSubAgent.php; 2026-11-08, live: no account
    const val NAPISY24_LOGIN_ERROR = "login error"

    // Legendas.net: POST /login, GET /search/movie, /search/tv; 2026-11-08, live: a wrong account
    const val LEGENDAS_WRONG_LOGIN = """{"message":"Invalid username/password","status":401}"""
    const val LEGENDAS_MOVIES = """{"success":true,"movies":[
        {"id":5,"tmdb_id":603,"path":"/storage/legendas/the-matrix.zip","release_name":"The.Matrix.1999.1080p","uploader":"x","comment":""},
        {"id":6,"tmdb_id":603,"path":"https://legendas.net/storage/legendas/forced.zip","release_name":"The.Matrix.1999.Forced","uploader":"x","comment":"Forced: only the foreign parts"},
        {"id":7,"tmdb_id":603,"path":"//evil.example/x.zip","release_name":"x","uploader":"x"}]}"""
    const val LEGENDAS_TV = """{"success":true,"tv_shows":[
        {"id":8,"tmdb_id":1396,"path":"/storage/legendas/bb-s01e02.zip","release_name":"Breaking.Bad.S01E02","season":1,"episode":2,"uploader":"x"},
        {"id":9,"tmdb_id":1396,"path":"/storage/legendas/bb-s01e03.zip","release_name":"Breaking.Bad.S01E03","season":1,"episode":3,"uploader":"x"}]}"""
}
