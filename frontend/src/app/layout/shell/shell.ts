import { Component, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { BadgeModule } from 'primeng/badge';
import { Avatar } from 'primeng/avatar';
import { Button } from 'primeng/button';
import { AuthService } from '../../core/services/auth.service';
import { NotificationService } from '../../core/services/notification.service';
import { WebSocketService } from '../../core/services/websocket.service';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, BadgeModule, Avatar, Button],
  templateUrl: './shell.html',
  styleUrl: './shell.scss'
})
export class ShellLayout implements OnInit, OnDestroy {
  private auth = inject(AuthService);
  private notificationSvc = inject(NotificationService);
  private wsSvc = inject(WebSocketService);

  unreadCount = signal(0);

  ngOnInit(): void {
    this.notificationSvc.getNotifications().subscribe(list => {
      this.unreadCount.set(list.filter(n => !n.read).length);
    });

    this.wsSvc.connect();
    this.wsSvc.notification$.subscribe(() => {
      this.unreadCount.update(n => n + 1);
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
