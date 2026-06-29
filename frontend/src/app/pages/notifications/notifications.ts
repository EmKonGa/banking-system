import { Component, inject, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Card } from 'primeng/card';
import { Button } from 'primeng/button';
import { Tag } from 'primeng/tag';
import { ProgressSpinner } from 'primeng/progressspinner';
import { NotificationService } from '../../core/services/notification.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { Notification } from '../../core/models';

@Component({
  selector: 'app-notifications',
  imports: [DatePipe, Card, Button, Tag, ProgressSpinner],
  templateUrl: './notifications.html',
  styleUrl: './notifications.scss'
})
export class NotificationsPage implements OnInit {
  private notificationSvc = inject(NotificationService);
  private wsSvc = inject(WebSocketService);

  notifications = signal<Notification[]>([]);
  loading = signal(true);

  get unreadCount(): number {
    return this.notifications().filter(n => !n.read).length;
  }

  ngOnInit(): void {
    this.load();
    this.wsSvc.notification$.subscribe(n => {
      this.notifications.update(list => [n, ...list]);
    });
  }

  load(): void {
    this.notificationSvc.getNotifications().subscribe(list => {
      this.notifications.set(list);
      this.loading.set(false);
    });
  }

  markRead(n: Notification): void {
    if (n.read) return;
    this.notificationSvc.markRead(n.id).subscribe(() => {
      this.notifications.update(list =>
        list.map(item => item.id === n.id ? { ...item, read: true } : item)
      );
    });
  }

  markAllRead(): void {
    this.notificationSvc.markAllRead().subscribe(() => {
      this.notifications.update(list => list.map(n => ({ ...n, read: true })));
    });
  }

  typeSeverity(type: string): 'success' | 'info' | 'warn' | 'secondary' {
    switch (type) {
      case 'PAYMENT_SENT': return 'warn';
      case 'PAYMENT_RECEIVED': return 'success';
      case 'ACCOUNT_CREATED': return 'info';
      default: return 'secondary';
    }
  }
}
