package app.iole.tv;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android will let Cleo read the active media sessions ("Now playing").
 * It doesn't read or store notifications.
 */
public class MediaListener extends NotificationListenerService {
}
