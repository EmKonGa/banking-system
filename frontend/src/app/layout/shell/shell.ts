import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { BadgeModule } from 'primeng/badge';
import { Avatar } from 'primeng/avatar';
import { Button } from 'primeng/button';
import { AuthService } from '../../core/services/auth.service';
import { NotificationService } from '../../core/services/notification.service';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, BadgeModule, Avatar, Button],
  templateUrl: './shell.html',
  styleUrl: './shell.scss'
})
export class ShellLayout implements OnInit {
  private auth = inject(AuthService);
  private notificationSvc = inject(NotificationService);

  unreadCount = signal(0);

  ngOnInit(): void {
    this.notificationSvc.getNotifications().subscribe(list => {
      this.unreadCount.set(list.filter(n => !n.read).length);
    });
  }

  logout(): void {
    this.auth.logout();
  }
}
