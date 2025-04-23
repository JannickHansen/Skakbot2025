package Util;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;

public class PieceImageLoader {
    private static BufferedImage spriteSheet;
    private static final int COLS = 6;
    private static final int ROWS = 2;
    private static int tileWidth;
    private static int tileHeight;

    static {
        try {
            // Sørg for at stien matcher præcis hvor du har lagt png'en i dine resources
            spriteSheet = ImageIO.read(PieceImageLoader.class.getResource("/resources/pieces.png"));

            // Dynamisk beregning
            tileWidth  = spriteSheet.getWidth()  / COLS;
            tileHeight = spriteSheet.getHeight() / ROWS;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static ImageIcon getPieceIcon(String type, boolean isWhite) {
        int row = isWhite ? 0 : 1;
        int col = switch (type.toLowerCase()) {
            case "king"   -> 0;
            case "queen"  -> 1;
            case "bishop" -> 2;
            case "knight" -> 3;
            case "rook"   -> 4;
            case "pawn"   -> 5;
            default -> throw new IllegalArgumentException("Unknown piece type: " + type);
        };

        BufferedImage sub = spriteSheet.getSubimage(
                col * tileWidth,
                row * tileHeight,
                tileWidth,
                tileHeight
        );

        Image scaled = sub.getScaledInstance(60, 60, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }
}
