import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of, throwError } from 'rxjs';
import { ConnectionResponse } from './connection';
import { ConnectionsListComponent } from './connections-list.component';
import { ConnectionsService } from './connections.service';

const office: ConnectionResponse = {
  resourceId: 'c1',
  displayName: 'Office OCI',
  hostname: 'bw.example.com',
  port: 2208,
  username: 'admin',
  needsPassword: true
};

const lab: ConnectionResponse = {
  resourceId: 'c2',
  displayName: 'Lab',
  hostname: 'lab.example.com',
  port: 2208,
  username: 'labuser',
  needsPassword: false
};

describe('ConnectionsListComponent', () => {
  let fixture: ComponentFixture<ConnectionsListComponent>;
  let service: {
    list: ReturnType<typeof vi.fn>;
    delete: ReturnType<typeof vi.fn>;
    verify: ReturnType<typeof vi.fn>;
  };

  async function setup(listImpl?: ConnectionsService['list']): Promise<void> {
    service = {
      list: vi.fn(listImpl ?? (() => of([office, lab]))),
      delete: vi.fn(() => of(undefined)),
      verify: vi.fn(() => of({ success: true, message: 'Login succeeded.' }))
    };

    await TestBed.configureTestingModule({
      imports: [ConnectionsListComponent],
      providers: [
        provideRouter([]),
        { provide: ConnectionsService, useValue: service }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ConnectionsListComponent);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('shows a loading spinner until the list arrives', async () => {
    await setup(() => NEVER);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.spinner')).toBeTruthy();
    expect(compiled.textContent).toContain('Loading BroadWorks connections');
    expect(compiled.querySelector('.connection-card')).toBeNull();
    expect(compiled.querySelector('table')).toBeNull();
  });

  it('shows an empty state when there are no connections', async () => {
    await setup(() => of([]));
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.empty-state')).toBeTruthy();
    expect(compiled.textContent).toContain('No BroadWorks connections yet');
    expect(compiled.textContent).toContain('Add connection');
    expect(compiled.querySelector('.connection-card')).toBeNull();
    expect(compiled.querySelector('table')).toBeNull();
  });

  it('renders a card per connection with host/user and Needs password', async () => {
    await setup();
    const compiled = fixture.nativeElement as HTMLElement;
    const cards = compiled.querySelectorAll('.connection-card');
    expect(cards.length).toBe(2);
    expect(compiled.querySelector('table')).toBeNull();
    expect(compiled.textContent).toContain('Office OCI');
    expect(compiled.textContent).toContain('admin@bw.example.com:2208');
    expect(compiled.textContent).toContain('Needs password');
    expect(compiled.textContent).toContain('Lab');
    expect(compiled.textContent).toContain('labuser@lab.example.com:2208');
    expect(compiled.textContent).toContain('Edit');
    expect(compiled.textContent).toContain('Verify');
    expect(compiled.textContent).toContain('Delete');
  });

  it('opens a confirm dialog for delete and does not use window.confirm', async () => {
    const nativeConfirm = vi.spyOn(window, 'confirm');
    await setup();
    const compiled = fixture.nativeElement as HTMLElement;

    compiled.querySelectorAll<HTMLButtonElement>('.btn-outline-danger')[0]?.click();
    fixture.detectChanges();

    expect(compiled.querySelector('[role="dialog"]')).toBeTruthy();
    expect(compiled.textContent).toContain('Delete connection "Office OCI"?');
    expect(nativeConfirm).not.toHaveBeenCalled();
    expect(service.delete).not.toHaveBeenCalled();

    compiled.querySelector<HTMLButtonElement>('.dialog-actions .btn-secondary')?.click();
    fixture.detectChanges();
    expect(compiled.querySelector('[role="dialog"]')).toBeNull();
    expect(service.delete).not.toHaveBeenCalled();
  });

  it('deletes after confirming in the dialog and reloads the list', async () => {
    await setup();
    service.list.mockReturnValueOnce(of([lab]));
    const compiled = fixture.nativeElement as HTMLElement;

    compiled.querySelectorAll<HTMLButtonElement>('.btn-outline-danger')[0]?.click();
    fixture.detectChanges();
    compiled.querySelector<HTMLButtonElement>('.dialog-actions .btn-danger')?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(service.delete).toHaveBeenCalledWith('c1');
    expect(compiled.querySelector('[role="dialog"]')).toBeNull();
    expect(compiled.textContent).not.toContain('Office OCI');
    expect(compiled.textContent).toContain('Lab');
  });

  it('verifies a connection through ConnectionsService', async () => {
    await setup();
    const compiled = fixture.nativeElement as HTMLElement;
    compiled.querySelector<HTMLButtonElement>('.connection-actions button.btn-secondary')?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(service.verify).toHaveBeenCalledWith({
      hostname: 'bw.example.com',
      port: 2208,
      username: 'admin',
      resourceId: 'c1'
    });
    expect(compiled.textContent).toContain('Login succeeded.');
  });

  it('shows a load error without using a table', async () => {
    await setup(() => throwError(() => ({ status: 500 })));
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Failed to load connections.');
    expect(compiled.querySelector('table')).toBeNull();
  });
});
