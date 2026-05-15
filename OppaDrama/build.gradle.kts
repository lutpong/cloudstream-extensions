// Use an integer for version numbers
version = 1

cloudstream {
    description = "Nonton drama Korea, Jepang, China, Thailand, film, dan anime subtitle Indonesia dari OPPADRAMA"
    authors = listOf("CloudstreamUser")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "AsianDrama", "Anime")

    language = "id"

    iconUrl = "https://raw.githubusercontent.com/recloudstream/cloudstream/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
}
