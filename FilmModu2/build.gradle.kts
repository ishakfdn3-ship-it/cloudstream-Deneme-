version = 1

cloudstream {
    language = "tr"
    description = "FilmModu Eklentisi"
    authors = listOf("İshak")

    /**
     * Durum kodları:
     * 0: Down (Çalışmıyor)
     * 1: Ok (Sorunsuz)
     * 2: Slow (Yavaş)
     * 3: Beta only (Sadece Beta)
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=filmmodu.cc&sz=%size%"
}
