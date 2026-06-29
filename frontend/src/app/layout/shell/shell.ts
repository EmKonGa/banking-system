import { Component, inject, OnDestroy, OnInit } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { BadgeModule } from 'primeng/badge';
import { Avatar } from 'primeng/avatar';
import { Button } from 'primeng/button';
import { Toast } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import { AuthService } from '../../core/services/auth.service';
import { NotificationService } from '../../core/services/notification.service';
import { WebSocketService } from '../../core/services/websocket.service';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, BadgeModule, Avatar, Button, Toast],
  providers: [MessageService],
  templateUrl: './shell.html',
  styleUrl: './shell.scss'
})
export class ShellLayout implements OnInit, OnDestroy {
  private auth = inject(AuthService);
  private notificationSvc = inject(NotificationService);
  private wsSvc = inject(WebSocketService);
  private msg = inject(MessageService);

  unreadCount = this.notificationSvc.unreadCount;

  ngOnInit(): void {
    this.notificationSvc.getNotifications().subscribe();

    this.wsSvc.connect();
    this.wsSvc.notification$.subscribe(n => {
      this.notificationSvc.incrementUnread();
      this.msg.add({
        severity: n.type === 'PAYMENT_RECEIVED' ? 'success' : 'info',
        summary: n.type === 'PAYMENT_RECEIVED' ? 'Money received' : 'Transfer sent',
        detail: n.message,
        life: 5000
      });
    });
  }

  ngOnDestroy(): void {
    this.wsSvc.disconnect();
  }

  logout(): void {
    this.wsSvc.disconnect();
    this.auth.logout();
  }
}
