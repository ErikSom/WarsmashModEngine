/**
 * Screen wake-lock helper. Mobile browsers throttle background tabs
 * aggressively: workers stall, OPFS writes drop to ~1Hz, and the user
 * comes back to a frozen page. Holding a screen wake lock during MPQ
 * extraction and engine play keeps the page foreground+active.
 *
 * Browsers without the API (older iOS) silently no-op.
 */

let wakeLock: WakeLockSentinel | null = null;

export async function acquireWakeLock(): Promise<void> {
  if (wakeLock || !('wakeLock' in navigator)) return;
  try {
    wakeLock = await navigator.wakeLock.request('screen');
    wakeLock.addEventListener('release', () => { wakeLock = null; });
  }
  catch {
    // Permission denied / page not visible / API unsupported — ignore.
  }
}

export function hasWakeLock(): boolean {
  return wakeLock !== null;
}

/** Wire the visibility-change listener that re-acquires the lock when
 *  the user comes back. Browsers auto-release on tab hide. Pass a
 *  predicate that returns true while the lock is still wanted. */
export function installWakeLockReacquire(stillWanted: () => boolean): void {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && stillWanted()) {
      acquireWakeLock();
    }
  });
}
