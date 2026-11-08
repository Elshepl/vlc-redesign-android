package org.videolan.vlc.util

/**
 * Saved answers of the subtitle services of a key or an account, for the parsers' tests. The answers with results
 * are written from the shapes the maintained implementations read (a4kSubtitles' services/subsource.py and subdl.py,
 * Cinephage's SubDLProvider.ts, Bazarr's providers); the error answers are what the services answered on 2026-11-08
 * to a wrong or missing key (no real key exists here yet).
 */
object SubtitleFixtures {

    // SubSource: GET /movies/search?searchType=imdb|text
    const val SUBSOURCE_FILM_SEARCH = """{"success":true,"data":[
        {"movieId":123,"title":"The Matrix Resurrections","imdbId":"tt10838180","type":"movie","releaseYear":2021},
        {"movieId":456,"title":"The Matrix","imdbId":"tt0133093","type":"movie","releaseYear":1999}]}"""
    const val SUBSOURCE_SERIES_SEARCH = """{"success":true,"data":[
        {"movieId":901,"title":"Breaking Bad - First Season","imdbId":"tt0903747","type":"tvseries","season":1}
        ,{"movieId":902,"title":"Breaking Bad - Second Season","imdbId":"tt0903747","type":"tvseries","season":2}]}"""

    // GET /subtitles?movieId=456&language=arabic,english,...
    const val SUBSOURCE_FILM_SUBTITLES = """{"success":true,"data":[
        {"subtitleId":9,"language":"arabic","releaseInfo":["The.Matrix.1999.1080p.BluRay.x264-YIFY","The.Matrix.1999.720p"],
         "hearingImpaired":false,"rating":{"good":12,"bad":0,"total":12},"link":"/subtitle/the-matrix/arabic/9"},
        {"subtitleId":10,"language":"farsi_persian","releaseInfo":["The.Matrix.1999.REMASTERED.2160p"],"hearingImpaired":true,
         "rating":{"total":3},"link":"/subtitle/the-matrix/farsi_persian/10"},
        {"subtitleId":11,"language":"brazilian_portuguese","releaseInfo":"The.Matrix.1999.DVDRip","hearingImpaired":false,
         "rating":{"total":0}},
        {"subtitleId":"../12","language":"english","releaseInfo":["x"]},
        {"subtitleId":13,"language":"","releaseInfo":["no language"]}]}"""

    // an episode's season (movieId 901): S01E02, S01E03, the season as a whole
    const val SUBSOURCE_SEASON_SUBTITLES = """{"success":true,"data":[
        {"subtitleId":21,"language":"arabic","releaseInfo":["Breaking.Bad.S01E02.720p.HDTV.x264"],"hearingImpaired":false},
        {"subtitleId":22,"language":"arabic","releaseInfo":["Breaking.Bad.S01E03.720p.HDTV.x264"],"hearingImpaired":false},
        {"subtitleId":23,"language":"english","releaseInfo":["Breaking.Bad.Season.1.Complete.BluRay"],"hearingImpaired":false}]}"""

    // 2026-11-08, live
    const val SUBSOURCE_NO_KEY = """{"error":"API key required","message":"Please provide an API key in the X-API-Key header or api_key query parameter"}"""
    const val SUBSOURCE_WRONG_KEY = """{"error":"Invalid API key","message":"The provided API key is invalid or inactive"}"""

    // SubDL: GET /api/v2/subtitles/search (v1 the same)
    const val SUBDL_FILM = """{"status":true,"results":[{"sd_id":1,"type":"movie","name":"The Matrix","imdb_id":"tt0133093","year":1999}],
        "subtitles":[
        {"release_name":"The.Matrix.1999.REMASTERED.1080p.BluRay","name":"SUBDL::The.Matrix.1999.zip","lang":"arabic","language":"AR",
         "author":"someone","url":"/subtitle/3197651-3213944.zip","season":null,"episode":null,"hi":false,"full_season":false},
        {"release_name":"The.Matrix.1999.720p.WEB","name":"x","lang":"english","language":"EN","author":"x",
         "url":"/subtitle/3197652-3213945.zip","hi":true,"full_season":false},
        {"release_name":"","name":"The Matrix","lang":"Brazillian Portuguese","language":"BR_PT","author":"x",
         "url":"/subtitle/3197653-3213946.zip","hi":false},
        {"release_name":"no link","lang":"english","language":"EN","url":""}]}"""

    const val SUBDL_EPISODES = """{"status":true,"subtitles":[
        {"release_name":"Breaking.Bad.S01E02.720p","lang":"arabic","language":"AR","url":"/subtitle/100-1.zip","season":1,"episode":2,"hi":false,"full_season":false},
        {"release_name":"Breaking.Bad.S01E03.720p","lang":"arabic","language":"AR","url":"/subtitle/100-2.zip","season":1,"episode":3,"hi":false,"full_season":false},
        {"release_name":"Breaking.Bad.Season.1.Complete","lang":"english","language":"EN","url":"/subtitle/100-3.zip","season":1,"episode":0,"hi":false,"full_season":true},
        {"release_name":"Breaking.Bad.S02E02.720p","lang":"english","language":"EN","url":"/subtitle/100-4.zip","season":2,"episode":2,"hi":false,"full_season":false}]}"""

    const val SUBDL_NOT_FOUND = """{"status":false,"error":"can't find movie or tv"}"""

    // 2026-11-08, live
    const val SUBDL_V2_WRONG_KEY = """{"error":{"code":"unauthorized","message":"Not Authorized","docs_url":"https://subdl.com/developers#errors"}}"""
    const val SUBDL_V1_WRONG_KEY = """{"status":false,"statusCode":403,"error":"not_authorized","message":"Not Authorized"}"""

    // OpenSubtitles.com, 2026-11-08, live (a wrong Api-Key)
    const val OSCOM_WRONG_KEY = """{
  "message":"You cannot consume this service",
  "request_id":"f7846f76c10c48611646464004b0486b"
}"""
}
