/**
 * /assets orchestrator — composes AssetUploader (folder picker +
 * progress) with MapsManager (list / add / remove uploaded maps).
 * The maps section lazily mounts after assets are present so we
 * don't hit OPFS for an empty /w3/Maps/.
 */
import { useState } from 'preact/hooks';
import AssetUploader from './AssetUploader';
import MapsManager from './MapsManager';

export default function AssetsPage() {
  const [showMaps, setShowMaps] = useState(false);

  return (
    <main class="multiplayer-page">
      <header class="mp-header">
        <h1>Manage assets</h1>
        <div class="mp-header-actions">
          <a href="../" class="secondary btn">Home</a>
        </div>
      </header>

      <AssetUploader />

      <section class="mp-section">
        <div class="mp-section-header">
          <h2>Maps</h2>
          <button class="secondary" onClick={() => setShowMaps(true)}>Open maps manager</button>
        </div>
        <p class="mp-section-hint">
          Add custom <code>.w3x</code> / <code>.w3m</code> files (uploaded into <code>/w3/Maps/Upload/</code>)
          or remove existing entries. Stock maps from your install are listed alongside.
        </p>
      </section>

      {showMaps && <MapsManager onClose={() => setShowMaps(false)} />}
    </main>
  );
}
