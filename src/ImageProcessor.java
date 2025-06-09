import javax.imageio.ImageIO; // для чтения/записи изображений разных форматов
import java.awt.image.BufferedImage; // класс для работы с изображениями в памяти (изменение картинки)
import java.io.IOException; 
import java.nio.file.Files; // работа с файлами
import java.nio.file.Path; // для пути к файлу/папке
import java.nio.file.Paths; // создание объекта Paths из строки
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService; // управление пулом потоков
import java.util.concurrent.Executors; // для создания ExecutorsServise
import java.util.concurrent.Future; // результат асинхронной задачи 
import java.util.concurrent.TimeUnit; // для времени 
import java.util.concurrent.atomic.AtomicBoolean; // для отмены операций

public class ImageProcessor {
    private static final List<String> IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".bmp", ".gif");
    private static final AtomicBoolean isCancelled = new AtomicBoolean(false);

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Недостаточно аргументов");
            return;
        }

        // Парсинг аргументов
        String sourceDir = args[0]; // путь к исходной директории 
        boolean recursive = false; // рекурсивный обход
        String operation = null; // тип операции
        Double scaleFactor = null; // коэффициент масштабирования 
        String copyDir = null; // путь для копирования 

        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("/sub")) {  // включение рекурсивного обхода подкаталогов
                recursive = true;
            } else if (args[i].equalsIgnoreCase("/s")) {
                operation = "scale";
                if (i + 1 < args.length) {
                    try {
                        scaleFactor = Double.parseDouble(args[i + 1]);
                        i++;
                    } catch (NumberFormatException e) {
                        System.out.println("Неверный коэффициент масштабирования");
                        return;
                    }
                } else {
                    System.out.println("Отсутствует коэффициент масштабирования");
                    return;
                }
            } else if (args[i].equalsIgnoreCase("/n")) {
                operation = "negative";
            } else if (args[i].equalsIgnoreCase("/r")) {
                operation = "remove";
            } else if (args[i].equalsIgnoreCase("/c")) {
                operation = "copy";
                if (i + 1 < args.length) {
                    copyDir = args[i + 1];
                    i++;
                } else {
                    System.out.println("Отсутствует путь для копирования");
                    return;
                }
            }
        }

        if (operation == null) {
            System.out.println("Не указана операция");
            return;
        }

        // Запуск обработки
        System.out.println("Начало обработки. Нажмите Esc для отмены.");
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        // создание пула потоков с фиксированным количеством
        List<Future<?>> futures = new ArrayList<>();
        // список всех запущенных задач со статусом

        try {
            processDirectory(Paths.get(sourceDir), recursive, operation, scaleFactor, copyDir, executor, futures);

            // Ожидание нажатия Esc
            Thread cancelThread = new Thread(() -> {
                try (Scanner scanner = new Scanner(System.in)) {
                    while (!isCancelled.get()) {
                        if (System.in.available() > 0) {
                            int input = System.in.read();
                            if (input == 27) { // ESC key
                                isCancelled.set(true);
                                System.out.println("\nОтмена операции...");
                                break;
                            }
                        }
                        Thread.sleep(100);
                    }
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            });
            cancelThread.setDaemon(true); // не препятствует завершению программы 
            cancelThread.start();

            // Ожидание завершения всех задач
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    // Игнорируем исключения, так как они уже обработаны в задачах
                }
            }

            if (isCancelled.get()) { // если нажали esc
                executor.shutdownNow();
            } else {
                executor.shutdown();
                executor.awaitTermination(1, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            e.printStackTrace(); // информация об ошибке
        } finally {
            System.out.println("Обработка завершена");
        }
    }

    private static void processDirectory(Path dir, boolean recursive, String operation, 
                                       Double scaleFactor, String copyDir, 
                                       ExecutorService executor, List<Future<?>> futures) {
        // рекурсивный многопоточный обход каталогов                                
        if (isCancelled.get()) { // если нажали esc
            return;
        }

        try {
            Files.list(dir).forEach(path -> { // способ перебора всех файлов
                if (isCancelled.get()) {
                    return;
                }

                if (Files.isDirectory(path)) { // рекурсия, если папка 
                    if (recursive) {
                        processDirectory(path, true, operation, scaleFactor, copyDir, executor, futures);
                    }
                } else {
                    String fileName = path.getFileName().toString().toLowerCase(); // если файл 
                    if (IMAGE_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
                        // проверяет расширение 
                        futures.add(executor.submit(() -> {
                            try {
                                processImage(path, operation, scaleFactor, copyDir);
                            // лямбда-выражение создаёт задачу
                            // executor.submit отправляет её в пул 
                            } catch (Exception e) {
                                System.err.println("Ошибка при обработке файла " + path + ": " + e.getMessage());
                            }
                        }));
                    }
                }
            });
        } catch (IOException e) {
            System.err.println("Ошибка при чтении каталога " + dir + ": " + e.getMessage());
        }
    }

    private static void processImage(Path imagePath, String operation, 
                                   Double scaleFactor, String copyDir) throws IOException {
        if (isCancelled.get()) {
            return;
        }

        switch (operation) {
            case "scale":
                scaleImage(imagePath, scaleFactor);
                break;
            case "negative":
                createNegative(imagePath);
                break;
            case "remove":
                removeImage(imagePath);
                break;
            case "copy":
                copyImage(imagePath, copyDir);
                break;
        }
    }

    private static void scaleImage(Path imagePath, double scaleFactor) throws IOException {
        BufferedImage originalImage = ImageIO.read(imagePath.toFile()); // загрузка изображение из файла
        if (originalImage == null) {
            return;
        }

        int newWidth = (int) (originalImage.getWidth() * scaleFactor);
        int newHeight = (int) (originalImage.getHeight() * scaleFactor);

        BufferedImage scaledImage = new BufferedImage(newWidth, newHeight, originalImage.getType());
        // getType() сохраняет тип исходного изображения (rgb)
        var graphics = scaledImage.createGraphics(); // создание объекта для рисования
        graphics.drawImage(originalImage, 0, 0, newWidth, newHeight, null); // масштабирование 
        graphics.dispose(); // освобождение ресурсов

        String formatName = getFormatName(imagePath); // определение формата по расширению 
        ImageIO.write(scaledImage, formatName, imagePath.toFile()); 
        // сохраняет изображение, переписывая исходный файл
        System.out.println("Изображение масштабировано: " + imagePath);
    }

    private static void createNegative(Path imagePath) throws IOException {
        BufferedImage originalImage = ImageIO.read(imagePath.toFile());
        if (originalImage == null) {
            return;
        }

        for (int y = 0; y < originalImage.getHeight(); y++) { // обработка пикселей по двум осям
            for (int x = 0; x < originalImage.getWidth(); x++) {
                int rgb = originalImage.getRGB(x, y);
                int a = (rgb >> 24) & 0xff;
                int r = 255 - ((rgb >> 16) & 0xff);
                int g = 255 - ((rgb >> 8) & 0xff);
                int b = 255 - (rgb & 0xff);
                // 0xff берёт два символа 
                rgb = (a << 24) | (r << 16) | (g << 8) | b;
                // сдвиг так, чтобы получилось argb по порядку 
                originalImage.setRGB(x, y, rgb);
            }
        }

        String formatName = getFormatName(imagePath);
        ImageIO.write(originalImage, formatName, imagePath.toFile());
        System.out.println("Создан негатив: " + imagePath);
    }

    private static void removeImage(Path imagePath) throws IOException {
        Files.delete(imagePath);
        System.out.println("Файл удален: " + imagePath);
    }

    private static void copyImage(Path imagePath, String copyDir) throws IOException {
        Path targetDir = Paths.get(copyDir); // преобразует строку пути в объект Path
        if (!Files.exists(targetDir)) { // проверяет существование директории
            Files.createDirectories(targetDir); // создаёт все недостающие папки в пути
        }

        Path targetPath = targetDir.resolve(imagePath.getFileName()); // resolve() объединяет путь директории и имя файла
        Files.copy(imagePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        // StandardCopyOption.REPLACE_EXISTING: перезаписывает файл, если он уже существует
        System.out.println("Файл скопирован: " + imagePath + " -> " + targetPath);
    }

    private static String getFormatName(Path imagePath) {
        String fileName = imagePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "jpg";
        } else if (fileName.endsWith(".png")) {
            return "png";
        } else if (fileName.endsWith(".bmp")) {
            return "bmp";
        } else if (fileName.endsWith(".gif")) {
            return "gif";
        }
        return "jpg"; // по умолчанию
    }
}