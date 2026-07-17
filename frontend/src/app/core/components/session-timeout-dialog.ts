import { Component, computed, inject } from '@angular/core';
import { Dialog } from 'primeng/dialog';
import { Button } from 'primeng/button';
import { AuthService } from '../services/auth.service';

/**
 * Idle-session warning. When AuthService starts its pre-logout countdown, this
 * shows a blocking "stay logged in?" dialog. Ignoring it lets the countdown hit
 * zero and log the user out; "Stay logged in" refreshes the session.
 */
@Component({
  selector: 'app-session-timeout-dialog',
  imports: [Dialog, Button],
  template: `
    <p-dialog
      header="Session about to expire"
      [visible]="visible()"
      [modal]="true"
      [closable]="false"
      [draggable]="false"
      [style]="{ width: '380px' }"
    >
      <p>
        You've been inactive. For your security you'll be signed out in
        <strong>{{ auth.sessionWarning() }}</strong>
        second{{ auth.sessionWarning() === 1 ? '' : 's' }}.
      </p>
      <ng-template #footer>
        <p-button label="Log out now" [text]="true" severity="secondary" (onClick)="auth.logout()" />
        <p-button label="Stay logged in" (onClick)="auth.stayLoggedIn()" />
      </ng-template>
    </p-dialog>
  `
})
export class SessionTimeoutDialog {
  protected auth = inject(AuthService);
  protected visible = computed(() => this.auth.sessionWarning() !== null);
}
