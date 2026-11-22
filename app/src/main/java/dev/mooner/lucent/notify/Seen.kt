package dev.mooner.lucent.notify

/**
 * 사용자가 봤다는 것(`seen`)과 다른 기기에서 본 알림 치우기의 규칙(사양 5.6, 10.4, 11.1, 12.6, 결정 0066·0067).
 *
 * - **모자라게 알린다.** 잘못 알린 `seen`은 휴대폰으로 넘어갈 승인 카드를 막고, 알리지 못한 것은 한 번 더 울릴 뿐이다.
 *   그래서 대화 화면이 정말 보일 때만 그때까지 처리한 번호를 알린다(설정·넘겨받기 화면이 앞에 있으면 알리지 않는다).
 * - 처음 깔 때 기록으로 채운 번호까지([floor])는 프레임으로 보인 적이 없으니(상자의 카드는 기록에 없다) 그것만으로는
 *   알리지 않는다. 그 뒤의 것을 보면 번호 하나로 알리므로 함께 알려진다.
 * - **치우기도 모자라게.** 번호를 아는 알림(앱이 띄운 것)은 그 번호로 가린다. 번호를 모르는 알림(서버 푸시가 띄운 것)은
 *   이 기기에서 울렸을 수 있는 프레임이 모두 본 번호 안에 있을 때만 치운다.
 */
object Seen {

    /**
     * 지금 봤다고 알릴 번호. [visible]은 대화 화면이 앞에 있는가, [processed]는 처리한 마지막 번호, [reported]는 이미 알린
     * 번호, [floor]는 처음 기록으로 채운 번호다. 알릴 것이 없으면 null.
     */
    fun toReport(visible: Boolean, processed: Long, reported: Long, floor: Long): Long? =
        if (visible && processed > reported && processed > floor) processed else null

    /**
     * 다른 기기에서 [upTo]번까지 봤을 때, 번호를 모르는 알림까지 모두 치워도 되는가. [newestAlertable]은 이 기기에서 울렸을 수
     * 있는(`text`·`files`·`card`·`sticker`) 가장 새 프레임의 번호다. 모르면(null) 치우지 않는다.
     */
    fun clearsAll(upTo: Long, newestAlertable: Long?): Boolean = newestAlertable != null && upTo >= newestAlertable

    /** 번호를 다시 매긴 데몬(`resumed.last`가 앱의 번호보다 작다)에 맞춰 알린 번호를 줄인다. */
    fun clamp(reported: Long, last: Long): Long = minOf(reported, maxOf(last, 0L))
}
