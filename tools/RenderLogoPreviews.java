import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.*;
import java.io.*;
import javax.imageio.ImageIO;

public class RenderLogoPreviews {
  static BufferedImage read(String p) throws Exception { return ImageIO.read(new File(p)); }
  static void write(BufferedImage b, String p) throws Exception { ImageIO.write(b, "png", new File(p)); }
  static BufferedImage base(BufferedImage src, int n) {
    BufferedImage out = new BufferedImage(n,n,BufferedImage.TYPE_INT_ARGB); Graphics2D g=out.createGraphics();
    g.setColor(Color.WHITE); g.fillRect(0,0,n,n); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    int inset=Math.round(n*26f/108f), size=n-2*inset; g.drawImage(src,inset,inset,size,size,null); g.dispose(); return out;
  }
  static BufferedImage mask(BufferedImage in, boolean circle) {
    int n=in.getWidth(); BufferedImage out=new BufferedImage(n,n,BufferedImage.TYPE_INT_ARGB); Graphics2D g=out.createGraphics();
    g.setClip(circle ? new java.awt.geom.Ellipse2D.Float(0,0,n,n) : new RoundRectangle2D.Float(0,0,n,n,n*.22f,n*.22f)); g.drawImage(in,0,0,null); g.dispose(); return out;
  }
  static BufferedImage cropViewport(BufferedImage in) { return in.getSubimage(18,18,72,72); }
  public static void main(String[] a) throws Exception {
    BufferedImage src=read(a[0]); File dir=new File(a[1]); dir.mkdirs();
    BufferedImage resource=base(src,108), viewport=cropViewport(resource);
    write(resource,new File(dir,"logo-108-safe.png").getPath()); write(mask(viewport,true),new File(dir,"logo-72-circle.png").getPath()); write(mask(viewport,false),new File(dir,"logo-72-squircle.png").getPath());
    BufferedImage small=mask(viewport,true); BufferedImage out=new BufferedImage(48,48,BufferedImage.TYPE_INT_ARGB); Graphics2D g=out.createGraphics(); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); g.drawImage(small,0,0,48,48,null); g.dispose(); write(out,new File(dir,"logo-48-circle.png").getPath());
  }
}
