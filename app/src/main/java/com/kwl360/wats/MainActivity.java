package com.kwl360.wats;
import android.app.*;import android.os.*;import android.content.*;import android.database.*;import android.database.MatrixCursor;import android.content.res.AssetFileDescriptor;import android.net.Uri;import android.widget.*;import java.io.*;import java.util.*;import org.json.*;
public class MainActivity extends Activity {
 static final String AUTH="com.kwl360.wats.stickercontentprovider",ID="kwl_demo";
 TextView status;
 @Override public void onCreate(Bundle b){super.onCreate(b);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(30,50,30,20);setContentView(root);TextView title=new TextView(this);title.setText("KWL360 WATS — اختبار ملصقات متحركة");title.setTextSize(23);root.addView(title);status=new TextView(this);status.setText("حزمة اختبار متحركة من ثلاثة ملصقات. اختر واتساب لإضافتها إلى درج الملصقات.");status.setTextSize(17);root.addView(status);
 button(root,"إضافة إلى واتساب","com.whatsapp");button(root,"إضافة إلى واتساب الأعمال","com.whatsapp.w4b");
 try{File dir=folder(this);if(!dir.exists()&&!dir.mkdirs())throw new IOException("folder");for(int i=1;i<=3;i++){String name="sticker-"+i+".webp";File f=new File(dir,name);if(!f.exists())copy("stickers/"+name,f);}File icon=new File(dir,"tray.png");if(!icon.exists())copy("stickers/tray.png",icon);status.append("\nالحزمة المحلية جاهزة.");}catch(Exception e){status.setText("فشل تجهيز الملصقات: "+e);}
 }
 void copy(String name,File out)throws Exception{try(InputStream in=getAssets().open(name);FileOutputStream dest=new FileOutputStream(out)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)dest.write(buf,0,n);}}
 void button(LinearLayout root,String label,String pkg){Button b=new Button(this);b.setText(label);root.addView(b);b.setOnClickListener(v->{Intent i=new Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK");i.setPackage(pkg);i.putExtra("sticker_pack_id",ID);i.putExtra("sticker_pack_authority",AUTH);i.putExtra("sticker_pack_name","KWL360 فلسطين المتحركة");try{startActivityForResult(i,1);}catch(Exception ex){status.setText("لم تُفتح شاشة إضافة واتساب: "+ex.getMessage());}});}
 static File folder(Context c){return new File(c.getFilesDir(),"packs/"+ID);}
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==1)status.setText(result==RESULT_OK?"أبلغ واتساب عن نجاح العملية. افحص درج الملصقات.":"عاد واتساب دون تأكيد النجاح. افحص شاشة واتساب ودرج الملصقات.");}
 public static class Provider extends ContentProvider {
  static final String[] META={"sticker_pack_identifier","sticker_pack_name","sticker_pack_publisher","sticker_pack_icon","android_play_store_link","ios_app_download_link","publisher_email","publisher_website","privacy_policy_website","license_agreement_website","image_data_version","avoid_cache","animated_sticker_pack"};
  static final String[] STICKERS={"sticker_file_name","sticker_emoji","sticker_accessibility_text"};
  @Override public boolean onCreate(){return true;}
  @Override public String getType(Uri uri){return uri.toString().endsWith(".png")?"image/png":"image/webp";}
  @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){List<String> p=uri.getPathSegments();if(p.size()==0)return null;if(p.get(0).equals("metadata")&&(p.size()==1||(p.size()==2&&p.get(1).equals(ID)))){MatrixCursor c=new MatrixCursor(META);c.addRow(new Object[]{ID,"KWL360 فلسطين المتحركة","KWL360","tray.png","","","","https://kwl360.com","","","1",0,1});return c;}if(p.size()==2&&p.get(0).equals("stickers")&&p.get(1).equals(ID)){MatrixCursor c=new MatrixCursor(STICKERS);for(int n=1;n<=3;n++)c.addRow(new Object[]{"sticker-"+n+".webp","🇵🇸","ملصق فلسطين"});return c;}return null;}
  @Override public AssetFileDescriptor openAssetFile(Uri uri,String mode)throws FileNotFoundException{List<String> p=uri.getPathSegments();if(p.size()!=3||!p.get(0).equals("stickers_asset")||!p.get(1).equals(ID)||!p.get(2).matches("tray\\.png|sticker-[123]\\.webp"))throw new FileNotFoundException();File f=new File(folder(getContext()),p.get(2));if(!f.isFile())throw new FileNotFoundException();return new AssetFileDescriptor(ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY),0,f.length());}
  @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{return openAssetFile(uri,mode).getParcelFileDescriptor();}
  @Override public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException();}
  @Override public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
  @Override public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
 }
}