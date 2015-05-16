package hr.ravilov.resizer;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public abstract class CommonActivity extends Activity implements ProgressDialog.OnCancelListener, Button.OnClickListener {
	protected static final String TAG = "ImageResizer";
	protected static final int COMPRESSION_QUALITY = 98;

	protected static enum Sizes {
		// average filesizes calculated using http://web.forret.com/tools/megapixel.asp
		HQ_PHOTO (3456, 2304, 3 * 1024 * 1024),
		PHOTO (2048, 1536, 1 * 1024 * 1024),
		DESKTOP (1280, 1024, 700 * 1024),
		EMAIL (800, 600, 300 * 1024),
		PREVIEW (480, 360, 50 * 1024),
		THUMBNAIL (160, 120, 10 * 1024),
		AVATAR (80, 80, 3 * 1024),
		;

		private static final Sizes _default = null;
		private static final Map<String, Sizes> sLookup = new HashMap<String, Sizes>();
		static {
			for (final Sizes x : values()) {
				if (x.equals(_default)) {
					continue;
				}
				sLookup.put(x.asString(), x);
			}
		}

		private final int width;
		private final int height;
		private final long size;

		public int width() {
			return width;
		}

		public int height() {
			return height;
		}

		public long size() {
			return size;
		}

		private Sizes(final int w, final int h, final long s) {
			width = w;
			height = h;
			size = s;
		}

		public static Sizes find(final String v) {
			return find(v, _default);
		}

		public static Sizes find(final String v, final Sizes def) {
			return Utils.coalesce(sLookup.get(asString(v)), def);
		}

		private static String asString(final String v) {
			if (v == null) {
				return null;
			}
			return v.toLowerCase(Locale.US);
		}

		public String asString() {
			return asString(toString());
		}
	}

	protected static final File cacheDir = new File(Utils.join(File.separator, new String[] {
		Environment.getExternalStorageDirectory().getAbsolutePath(),
		"Android",
		"data",
		Utils.myPackage(),
		"cache",
	}));
	protected ProgressDialog dialog = null;
	protected Uri uri = null;
	protected Path path = null;
	protected long filesize = -1;
	protected int width = -1;
	protected int height = -1;
	protected String mimeType = null;
	protected float aspect = 0;
	protected Sizes selected = null;
	protected ImageView image = null;
	protected ViewGroup content = null;
	protected Button save = null;
	protected Button share = null;
	protected Button cancel = null;
	protected Bitmap thumbnail = null;

	@Override
	protected void onCreate(final Bundle saved) {
		super.onCreate(saved);
		Prefs.init(this);
		dialog = ProgressDialog.show(this, "", getText(R.string.please_wait), true, true, this);
		try {
			final ViewGroup root = new LinearLayout(this);
			((LayoutInflater)getSystemService(Context.LAYOUT_INFLATER_SERVICE)).inflate(R.layout.main, root);
			image = (ImageView)root.findViewById(R.id.image);
		}
		catch (final Throwable ex) {
			image = null;
		}
		final Thread loader = new Thread() {
			@Override
			public void run() {
				try {
					uri = (Uri)getIntent().getExtras().get(Intent.EXTRA_STREAM);
					if (uri == null) {
						throw new NullPointerException("No stream provided");
					}
				}
				catch (final Throwable ex) {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							onFail(R.string.error_intent, ex);
						}
					});
					return;
				}
				try {
					final String scheme = uri.getScheme();
					if (scheme.equals("file")) {
						final String rest = uri.getSchemeSpecificPart();
						// skip the double-slash
						int skip = 2;
						// skip anything else before the initial slash
						while (!rest.substring(0, 1).equals(File.separator) && skip < rest.length() - 1) {
							skip++;
						}
						path = new Path(rest.substring(skip));
					} else if (scheme.equals("content")) {
						final Cursor c = getContentResolver().query(uri, new String[] { MediaStore.Images.Media.DATA }, null, null, null);
						try {
							if (c.moveToFirst()) {
								path = new Path(c.getString(0));
							}
						}
						finally {
							if (c != null) {
								c.close();
							}
						}
					}
					if (path == null) {
						throw new NullPointerException();
					}
				}
				catch (final Throwable ex) {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							onFail(R.string.error_path, ex);
						}
					});
					return;
				}
				try {
					final BitmapFactory.Options options = new BitmapFactory.Options();
					options.inJustDecodeBounds = true;
					BitmapFactory.decodeFile(path.toString(), options);
					options.inJustDecodeBounds = false;
					width = options.outWidth;
					height = options.outHeight;
					mimeType = options.outMimeType;
					if (mimeType == null || mimeType.length() <= 0) {
						throw new RuntimeException("invalid or unsupported format");
					}
					aspect = (float)width / (float)height;
				}
				catch (final Throwable ex) {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							onFail(R.string.error_bitmap, ex);
						}
					});
					return;
				}
				try {
					FileInputStream fis = null;
					try {
						fis = new FileInputStream(path.toString());
						filesize = fis.getChannel().size();
					}
					finally {
						try {
							if (fis != null) {
								fis.close();
							}
						}
						catch (final Throwable ignore) { }
					}
				}
				catch (final Throwable ex) {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							onFail(R.string.error_bitmap, ex);
						}
					});
					return;
				}
				try {
					thumbnail = loadScaledBitmap(
						(int)getResources().getDimension(R.dimen.thumbnail_width),
						(int)getResources().getDimension(R.dimen.thumbnail_height)
					);
				}
				catch (final Throwable ex) {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							onFail(R.string.error_bitmap, ex);
						}
					});
					return;
				}
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						onContinue();
					}
				});
			}
		};
		loader.setDaemon(true);
		loader.start();
		try {
			cacheDir.mkdirs();
			final String[] list = cacheDir.list();
			for (final String entry : list) {
				try {
					(new File(cacheDir, entry)).delete();
				}
				catch (final Throwable ignore) { }
			}
			(new File(cacheDir, ".nomedia")).createNewFile();
		}
		catch (final Throwable ignore) { }
	}

	@Override
	public void onConfigurationChanged(final Configuration config) {
		super.onConfigurationChanged(config);
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_about: {
				final String html = Utils.Template.process(getString(R.string.about), new Utils.Template.Var[] {
					new Utils.Template.Var("NAME", getString(getApplicationInfo().labelRes)),
					new Utils.Template.Var("VERSION", Utils.getMyVersion(this)),
					new Utils.Template.Var("BUILD", Utils.getMyBuild(this)),
				});
				final AlertDialog dialog = (new AlertDialog.Builder(this))
					.setTitle(R.string.menu_about)
					.setIcon(Utils.resizeDrawable(this, R.drawable.icon, 16, 16))
					.setMessage(Html.fromHtml(html))
					.setPositiveButton(R.string.button_ok, null)
					.create()
				;
				dialog.show();
				final TextView tv = (TextView)dialog.findViewById(android.R.id.message);
				if (tv != null) {
					tv.setMovementMethod(LinkMovementMethod.getInstance());
				}
				return true;
			}
			default: {
				break;
			}
		}
		return false;
	}

	protected Bitmap.CompressFormat getCompressionByType() {
		if (mimeType.equalsIgnoreCase("image/png")) {
			return Bitmap.CompressFormat.PNG;
		}
		return Bitmap.CompressFormat.JPEG;
	}

	protected String getMimeByType() {
		switch (getCompressionByType()) {
			case JPEG: {
				return "image/jpeg";
			}
			case PNG: {
				return "image/png";
			}
			default: {
				break;
			}
		}
		return null;
	}

	protected String getExtensionByType() {
		switch (getCompressionByType()) {
			case JPEG: {
				return "jpg";
			}
			case PNG: {
				return "png";
			}
			default: {
				break;
			}
		}
		return null;
	}

	protected File getNewFile(final String suffix) {
		int i = 0;
		while (i <= 100) {
			final File file = new File(path.getDirectory(), path.getFilename() + suffix + ((i <= 0) ? "" : String.valueOf(i)) + "." + getExtensionByType());
			if (!file.exists()) {
				return file;
			}
			i++;
		}
		return null;
	}

	protected int getSizeWidth(final Sizes s, final boolean exact) {
		if (aspect > 1f) {
			return s.width();
		}
		if (aspect < 1f) {
			return s.height();
		}
		return exact ? s.width() : Math.min(s.width(), s.height());
	}

	protected int getSizeHeight(final Sizes s, final boolean exact) {
		if (aspect > 1f) {
			return s.height();
		}
		if (aspect < 1f) {
			return s.width();
		}
		return exact ? s.height() : Math.min(s.width(), s.height());
	}

	protected Bitmap loadScaledBitmap(int w, int h) {
		if (w >= width && h >= height) {
			return BitmapFactory.decodeFile(path.toString());
		}
		final float aspect2 = (float)w / (float)h;
		if (aspect > aspect2) {
			h = (int)Math.round(Math.floor((float)w / aspect));
		}
		if (aspect < aspect2) {
			w = (int)Math.round(Math.floor((float)h * aspect));
		}
		final int halfHeight = height / 2;
		final int halfWidth = width / 2;
		int sampleSize = 1;
		while ((halfWidth / sampleSize) > w && (halfHeight / sampleSize) > h) {
			sampleSize *= 2;
		}
		final BitmapFactory.Options options = new BitmapFactory.Options();
		options.inSampleSize = sampleSize;
		return Bitmap.createScaledBitmap(BitmapFactory.decodeFile(path.toString(), options), w, h, true);
	}

	@Override
	public void onCancel(final DialogInterface dialog) {
		if (dialog != null) {
			dialog.dismiss();
		}
		finish();
	}

	protected void onFail(final int resId, final Throwable ex) {
		onCancel(dialog);
		dialog = null;
		Toast.makeText(this, getString(resId, Utils.getExceptionMessage(ex)), Toast.LENGTH_LONG).show();
		Log.e(TAG, Utils.getStackTrace(ex));
	}

	protected void onContinue() {
		setContentView(R.layout.main);
		final TextView filename = (TextView)findViewById(R.id.filename);
		final TextView resolution = (TextView)findViewById(R.id.resolution);
		final TextView fsize = (TextView)findViewById(R.id.filesize);
		final TextView warning = (TextView)findViewById(R.id.gif_warning);
		content = (ViewGroup)findViewById(R.id.content);
		save = (Button)findViewById(R.id.btn_save);
		share = (Button)findViewById(R.id.btn_share);
		cancel = (Button)findViewById(R.id.btn_cancel);
		image = (ImageView)findViewById(R.id.image);
		if (filename != null) {
			final String ext = (path.getExtension().length() > 0) ? "." + path.getExtension() : "";
			filename.setText(path.getFilename() + ext);
		}
		if (resolution != null) {
			resolution.setText(getString(R.string.info_resolution, width, height));
		}
		if (fsize != null) {
			fsize.setText(formatSize(filesize));
		}
		if (warning != null) {
			final boolean isGif = mimeType.toLowerCase(Locale.US).equals("image/gif") ? true : false;
			if (isGif) {
				warning.setVisibility(View.VISIBLE);
				warning.setText(Html.fromHtml(warning.getText().toString()));
			} else {
				warning.setVisibility(View.GONE);
			}
		}
		if (image != null) {
			image.setImageBitmap(thumbnail);
		}
		if (save != null) {
			save.setOnClickListener(this);
		}
		if (share != null) {
			share.setOnClickListener(this);
		}
		if (cancel != null) {
			cancel.setOnClickListener(this);
		}
		dialog.dismiss();
		dialog = null;
	}

	protected String formatSize(final long size) {
		return formatSize(size, R.string.info_filesize);
	}

	protected String formatSize(final long size, final int resId) {
		final String[] units = getResources().getStringArray(R.array.filesize_units);
		int i = 0;
		float s = (float)size;
		while (s >= 1024f && i < units.length - 1) {
			s /= 1024f;
			i++;
		}
		return getString(resId, s, units[i]);
	}

	protected void mediaScan(final String filename, final String mime) {
		try {
			final Object sync = new Object();
			final MediaScannerConnection media = new MediaScannerConnection(CommonActivity.this, new MediaScannerConnection.MediaScannerConnectionClient() {
				private void unlock() {
					synchronized (sync) {
						try {
							sync.notify();
						}
						catch (final Throwable ignore) { }
					}
				}
	
				@Override
				public void onMediaScannerConnected() {
					unlock();
				}
	
				@Override
				public void onScanCompleted(final String path, final Uri uri) {
					unlock();
				}
			});
			try {
				media.connect();
				synchronized (sync) {
					try {
						sync.wait();
					}
					catch (final Throwable ignore) { }
				}
				media.scanFile(filename, mime);
				synchronized (sync) {
					try {
						sync.wait();
					}
					catch (final Throwable ignore) { }
				}
				media.disconnect();
			}
			finally {
				try {
					media.disconnect();
				}
				catch (final Throwable ignore) { }
			}
		}
		catch (final Throwable ignore) { }
	}

	protected void startExport(final Export task) {
		final Thread exporter = new Thread() {
			@Override
			public void run() {
				try {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							task.pre();
						}
					});
					task.run();
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							try {
								task.cleanup();
							}
							catch (final Throwable ignore) { }
							task.post();
						}
					});
				}
				catch (final Throwable ex) {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							try {
								task.cleanup();
							}
							catch (final Throwable ignore) { }
							task.fail(ex);
						}
					});
				}
			}
		};
		exporter.setDaemon(true);
		exporter.start();
	}

	protected Path onSave(final boolean isTemp) throws Throwable {
		return null;
	}

	private String intentInitialExtra() {
		try {
			final Field f = Intent.class.getDeclaredField("EXTRA_INITIAL_INTENTS");
			if (f != null) {
				f.setAccessible(true);
				return (String)f.get(null);
			}
		}
		catch (final Throwable ignore) { }
		return "android.intent.extra.INITIAL_INTENTS";
	}

	protected void onShare(final Path file, final String mime) {
		final Uri uri = Uri.parse("file://" + file.toString());
		final Intent intent = new Intent(Intent.ACTION_SEND);
		intent.addCategory(Intent.CATEGORY_DEFAULT);
		intent.setData(uri);
		intent.setType(getMimeByType());
		intent.putExtra(Intent.EXTRA_STREAM, uri);
		final Intent chooser = new Intent(Intent.ACTION_CHOOSER);
		chooser.putExtra(Intent.EXTRA_INTENT, intent);
		chooser.putExtra(Intent.EXTRA_TITLE, getText(R.string.share_using));
		final String pkg = Utils.myPackage();
		final List<Intent> list = new ArrayList<Intent>();
		final List<ResolveInfo> resInfo = getPackageManager().queryIntentActivities(intent, 0);
		for (final ResolveInfo ri : resInfo) {
			if (ri.activityInfo.packageName.equals(pkg)) {
				continue;
			}
			final Intent entry = new Intent(intent);
			entry.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
			list.add(entry);
		}
		chooser.putExtra(intentInitialExtra(), list.toArray(new Intent[list.size()]));
//		intent.addCategory(Intent.CATEGORY_TEST);
		startActivity(chooser);
		finish();
	}

	protected void onCancel() {
		finish();
	}

	@Override
	public void onClick(final View view) {
		switch (view.getId()) {
			case R.id.btn_save: {
				startExport(new Export() {
					protected Path newPath = null;

					@Override
					public void run() throws Throwable {
						newPath = onSave(false);
						if (newPath != null) {
							mediaScan(newPath.toString(), getMimeByType());
						}
					}

					@Override
					public void post() {
						finish();
						if (newPath != null) {
							Toast.makeText(CommonActivity.this, getString(R.string.msg_saved, newPath), Toast.LENGTH_LONG).show();
						}
					}

					@Override
					public void fail(final Throwable ex) {
						onFail(R.string.error_save, ex);
					}
				});
				break;
			}
			case R.id.btn_share: {
				startExport(new Export() {
					protected Path newPath = null;

					@Override
					public void run() throws Throwable {
						newPath = onSave(true);
					}

					@Override
					public void post() {
						onShare(newPath, getMimeByType());
					}

					@Override
					public void fail(final Throwable ex) {
						onFail(R.string.error_save_temp, ex);
					}
				});
				break;
			}
			case R.id.btn_cancel: {
				onCancel();
				break;
			}
			default: {
				break;
			}
		}
	}

	protected int getEntryResId() {
		return -1;
	}

	protected static class Path {
		private final String dir;
		private final String file;
		private final String ext;

		public Path(final String f) {
			if (f == null) {
				throw new NullPointerException("path is null");
			}
			final int lastSlash = f.lastIndexOf(File.separator);
			dir = (lastSlash < 0) ? "" : f.substring(0, lastSlash);
			final int lastDot = f.lastIndexOf('.');
			file = f.substring(Math.max(lastSlash, -1) + 1, (lastDot < 0) ? f.length() : lastDot);
			ext = (lastDot < 0) ? "" : f.substring(lastDot + 1);
		}

		public String getDirectory() {
			return dir;
		}

		public String getFilename() {
			return file;
		}

		public String getExtension() {
			return ext;
		}

		@Override
		public String toString() {
			return ((dir.length() > 0) ? dir + File.separator : "") + file + ((ext.length() > 0) ? "." + ext : "");
		}
	}

	protected class Export {
		public void pre() {
			dialog = ProgressDialog.show(CommonActivity.this, "", getText(R.string.please_wait), true, false, null);
		}

		public void run() throws Throwable {
		}

		public void post() {
		}

		public void fail(final Throwable ex) {
		}

		public void cleanup() {
			if (dialog != null) {
				dialog.dismiss();
				dialog = null;
			}
		}
	}

	protected class SizesArrayAdapter extends ArrayAdapter<Sizes> {
		private final CharSequence[] names;

		public SizesArrayAdapter() {
			super(CommonActivity.this, R.layout.spinner_dropdown_entry, android.R.id.text1, new ArrayList<Sizes>());
			setDropDownViewResource(R.layout.spinner_list_entry);
			names = getResources().getTextArray(R.array.sizes);
		}

		private void setItemData(final View view, final int position) {
			final int resId = getEntryResId();
			if (resId <= 0) {
				return;
			}
			final Sizes size = getItem(position);
			final TextView tv1 = (TextView)view.findViewById(android.R.id.text1);
			final TextView tv2 = (TextView)view.findViewById(android.R.id.text2);
			final String desc = getString(resId, getSizeWidth(size, true), getSizeHeight(size, true), formatSize(size.size(), R.string.option_filesize));
			if (tv1 != null) {
				tv1.setText(names[size.ordinal()] + ((tv2 == null) ? " " + desc : ""));
			}
			if (tv2 != null) {
				tv2.setText(desc);
			}
		}

		@Override
		public View getView(final int position, final View convertView, final ViewGroup parent) {
			final View view = super.getView(position, convertView, parent);
			setItemData(view, position);
			return view;
		}

		@Override
		public View getDropDownView(final int position, final View convertView, final ViewGroup parent) {
			final View view = super.getDropDownView(position, convertView, parent);
			setItemData(view, position);
			return view;
		}
	}

	protected static class Prefs {
		private Prefs() {
		}

		private static SharedPreferences sp = null;

		public static void init(final Activity a) {
			sp = PreferenceManager.getDefaultSharedPreferences(a);
		}

		public static String get(final String key) {
			if (sp == null) {
				return null;
			}
			return sp.getString(key, null);
		}

		public static void put(final String key, final String value) {
			if (sp == null) {
				return;
			}
			sp.edit().putString(key, value).commit();
		}

		public static void del(final String key) {
			if (sp == null) {
				return;
			}
			sp.edit().remove(key).commit();
		}
	}
}
