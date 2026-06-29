import { Injectable, inject } from '@angular/core';
import { Client, IMessage } from '@stomp/stompjs';
import { Subject } from 'rxjs';
import { AuthService } from './auth.service';
import { Notification, Account } from '../models';

@Injectable({ providedIn: 'root' })
export class WebSocketService {
  private authSvc = inject(AuthService);
  private client: Client | null = null;

  readonly notification$ = new Subject<Notification>();
  readonly balance$ = new Subject<Account>();

  connect(): void {
    if (this.client?.active) return;

    const token = this.authSvc.getAccessToken();
    if (!token) return;

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${protocol}//${window.location.host}/ws`;

    this.client = new Client({
      brokerURL,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 5000,
      onConnect: () => {
        this.client!.subscribe('/user/queue/notifications', (msg: IMessage) => {
          this.notification$.next(JSON.parse(msg.body));
        });
        this.client!.subscribe('/user/queue/balance', (msg: IMessage) => {
          this.balance$.next(JSON.parse(msg.body));
        });
      }
    });

    this.client.activate();
  }

  disconnect(): void {
    this.client?.deactivate();
    this.client = null;
  }
}
