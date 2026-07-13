import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { map } from 'rxjs/operators';
import { Notification } from '../models';

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private http = inject(HttpClient);

  readonly unreadCount = signal(0);

  getNotifications(): Observable<Notification[]> {
    return this.http.get<{ content: Notification[] }>('/api/notifications').pipe(
      map(page => page.content),
      tap(list => this.unreadCount.set(list.filter(n => !n.read).length))
    );
  }

  markRead(id: string): Observable<void> {
    return this.http.patch<void>(`/api/notifications/${id}/read`, {}).pipe(
      tap(() => this.unreadCount.update(n => Math.max(0, n - 1)))
    );
  }

  markAllRead(): Observable<void> {
    return this.http.patch<void>('/api/notifications/read-all', {}).pipe(
      tap(() => this.unreadCount.set(0))
    );
  }

  incrementUnread(): void {
    this.unreadCount.update(n => n + 1);
  }
}
