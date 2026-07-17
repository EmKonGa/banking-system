import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SessionTimeoutDialog } from './core/components/session-timeout-dialog';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, SessionTimeoutDialog],
  template: '<router-outlet /><app-session-timeout-dialog />'
})
export class App {}
