package com.vn.service;

import com.vn.service.notification.EmailNotificationCommand;
import com.vn.service.notification.NotificationEnqueueResult;

public interface NotificationQueueService {

    NotificationEnqueueResult enqueueEmail(EmailNotificationCommand command);
}
