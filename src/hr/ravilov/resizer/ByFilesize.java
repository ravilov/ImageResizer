package hr.ravilov.resizer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Spinner;

public class ByFilesize extends CommonActivity implements Spinner.OnItemSelectedListener {
	private static final String FILENAME_SUFFIX = "-reduced";
	private static final String PREF = "reduce_default";
	protected Spinner sizes = null;

	@Override
	protected void onContinue() {
		super.onContinue();
		((LayoutInflater)getSystemService(Context.LAYOUT_INFLATER_SERVICE)).inflate(R.layout.by_filesize, content);
		setButtons();
		final Sizes def = Sizes.find(Prefs.get(PREF));
		sizes = (Spinner)findViewById(R.id.sizes);
		int idx = -1;
		int cnt = 0;
		if (sizes != null) {
			final SizesArrayAdapter adapter = new SizesArrayAdapter();
			sizes.setAdapter(adapter);
			adapter.clear();
			for (final Sizes s : Sizes.values()) {
				if (s.size() >= filesize) {
					continue;
				}
				adapter.add(s);
				if (s.equals(def)) {
					idx = cnt;
				}
				cnt++;
			}
			sizes.setOnItemSelectedListener(this);
			if (idx >= 0) {
				sizes.setSelection(idx, false);
			}
		}
	}

	private void setButtons() {
		final boolean enabled = (selected == null) ? false : true;
		if (save != null) {
			save.setEnabled(enabled);
		}
		if (share != null) {
			share.setEnabled(enabled);
		}
	}

	@Override
	public void onItemSelected(final AdapterView<?> parent, final View view, final int pos, final long id) {
		selected = (Sizes)parent.getAdapter().getItem(pos);
		setButtons();
	}

	@Override
	public void onNothingSelected(final AdapterView<?> parent) {
		selected = null;
		setButtons();
	}

	@Override
	protected int getEntryResId() {
		return R.string.reduce_option;
	}

	@Override
	protected Path onSave(final boolean isTemp) throws Throwable {
		Prefs.put(PREF, selected.asString());
		final ByteArrayOutputStream buff = new ByteArrayOutputStream();
		int w = getSizeWidth(selected, false);
		int h = getSizeHeight(selected, false);
		final float a = (float)w / (float)h;
		boolean done = false;
		while (w > 0 && h > 0) {
			final Bitmap resized = loadScaledBitmap(w, h);
			buff.reset();
			resized.compress(getCompressionByType(), COMPRESSION_QUALITY, buff);
			buff.flush();
			buff.close();
			if (buff.size() <= selected.size()) {
				done = true;
				break;
			}
			w = (int)Math.round((float)w * 0.9);
			h = (int)Math.round((float)w / a);
		}
		if (!done) {
			throw new Exception("unable to resize image");
		}
		final File file = isTemp ? new File(cacheDir + File.separator + path.getFilename() + FILENAME_SUFFIX + "." + getExtensionByType()) : getNewFile(FILENAME_SUFFIX);
		final OutputStream os = new FileOutputStream(file);
		os.write(buff.toByteArray());
		os.flush();
		os.close();
		return new Path(file.getAbsolutePath());
	}
}
