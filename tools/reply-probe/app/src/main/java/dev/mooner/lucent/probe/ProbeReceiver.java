package dev.mooner.lucent.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.util.Log;

/**
 * 시험용 알림을 띄우고(POST), 그 알림의 답장을 받고(REPLY), 걷는다(CLEAR).
 *
 * POST의 덧붙임: room(단톡방 이름, 없으면 1:1), from(보낸 사람), text(글), style(messaging | kakao | plain).
 * - messaging: 메신저 알림의 표준 모양(MessagingStyle, 대화 이름과 메시지들).
 * - kakao: 카카오톡처럼 제목에 보낸 사람, 작은 글(subText)에 방 이름을 두고 MessagingStyle을 쓰지 않는다.
 * - plain: 답장 단추가 없는 보통 알림.
 */
public class ProbeReceiver extends BroadcastReceiver {

    private static final String TAG = "ReplyProbe";
    private static final String CHANNEL = "probe";
    private static final String KEY_REPLY = "probe_reply";
    private static final String ACTION_POST = "dev.mooner.lucent.probe.POST";
    private static final String ACTION_REPLY = "dev.mooner.lucent.probe.REPLY";
    private static final String ACTION_CLEAR = "dev.mooner.lucent.probe.CLEAR";
    private static final int ID = 1;

    @Override
    public void onReceive(Context context, Intent intent) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Probe", NotificationManager.IMPORTANCE_LOW));
        SharedPreferences prefs = context.getSharedPreferences("probe", Context.MODE_PRIVATE);
        String action = String.valueOf(intent.getAction());
        switch (action) {
            case ACTION_POST: {
                String style = or(intent.getStringExtra("style"), "messaging");
                String room = intent.getStringExtra("room");
                String from = or(intent.getStringExtra("from"), "Probe");
                String text = or(intent.getStringExtra("text"), "ping");
                prefs.edit().putString("style", style).putString("room", room).putString("from", from).putString("text", text)
                        .remove("reply").apply();
                manager.notify(ID, build(context, prefs));
                Log.i(TAG, "posted style=" + style);
                break;
            }
            case ACTION_REPLY: {
                Bundle results = RemoteInput.getResultsFromIntent(intent);
                CharSequence reply = results == null ? null : results.getCharSequence(KEY_REPLY);
                // 답장의 글은 로그에 남기지 않는다. 길이와 입력의 출처만 남긴다.
                Log.i(TAG, "reply received chars=" + (reply == null ? -1 : reply.length())
                        + " source=" + RemoteInput.getResultsSource(intent));
                if (reply != null) prefs.edit().putString("reply", reply.toString()).apply();
                manager.notify(ID, build(context, prefs));
                break;
            }
            case ACTION_CLEAR:
                manager.cancel(ID);
                prefs.edit().clear().apply();
                Log.i(TAG, "cleared");
                break;
            default:
                break;
        }
    }

    private static Notification build(Context context, SharedPreferences prefs) {
        String style = prefs.getString("style", "messaging");
        String room = prefs.getString("room", null);
        String from = prefs.getString("from", "Probe");
        String text = prefs.getString("text", "ping");
        String reply = prefs.getString("reply", null);

        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(Icon.createWithResource(context, android.R.drawable.stat_notify_chat))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setOnlyAlertOnce(true);
        if ("messaging".equals(style)) {
            Person me = new Person.Builder().setName("Me").build();
            Person other = new Person.Builder().setName(from).build();
            Notification.MessagingStyle messaging = new Notification.MessagingStyle(me);
            if (room != null) messaging.setConversationTitle(room).setGroupConversation(true);
            messaging.addMessage(text, System.currentTimeMillis() - 1000, other);
            if (reply != null) messaging.addMessage(reply, System.currentTimeMillis(), (Person) null);
            builder.setStyle(messaging);
        } else {
            builder.setContentTitle(from).setContentText(reply == null ? text : text + "\n> " + reply);
            if ("kakao".equals(style) && room != null) builder.setSubText(room);
        }
        if (!"plain".equals(style)) {
            Intent replyIntent = new Intent(context, ProbeReceiver.class).setAction(ACTION_REPLY);
            PendingIntent pending = PendingIntent.getBroadcast(context, 0, replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            RemoteInput input = new RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build();
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_send), "Reply", pending)
                    .addRemoteInput(input).build());
        }
        return builder.build();
    }

    private static String or(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
