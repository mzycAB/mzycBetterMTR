import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

public class FormatProbe {
    public static void main(String[] args) {
        VertexFormat f = DefaultVertexFormat.BLOCK;
        System.out.println("vertexSize=" + f.getVertexSize());
        System.out.println("integerSize=" + f.getIntegerSize());
        for (VertexFormatElement e : f.getElements()) {
            System.out.println("  " + e + " usage=" + e.usage() + " type=" + e.type()
                    + " count=" + e.count() + " byteSize=" + e.getByteSize());
        }
    }
}
