import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Notification } from '../models';

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private http = inject(HttpClient);

  getNotifications(): Observable<Notification[]> {
    return this.http.get<Notification[]>('/api/notifications');
  }

  markRead(id: string): Observable<void> {
    return this.http.patch<void>(`/api/notifications/${id}/read`, {});
  }

  markAllRead(): Observable<void> {
    return this.http.patch<void>('/api/notifications/read-all', {});
  }
}
