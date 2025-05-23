package com.mulion.telegram_bot_application;

import com.mulion.constants.BotMassageTexts;
import com.mulion.data_base.SessionProvider;
import com.mulion.data_base.repositories.BoatRepository;
import com.mulion.data_base.repositories.ReportRepository;
import com.mulion.data_base.repositories.UserRepository;
import com.mulion.data_base.services.DBBoatService;
import com.mulion.data_base.services.DBReportService;
import com.mulion.data_base.services.DBUserService;
import com.mulion.models.enums.Action;
import com.mulion.models.enums.UserRole;
import com.mulion.models.User;
import com.mulion.services.ConfigService;
import com.mulion.services.ReportService;
import com.mulion.telegram_bot_application.role_interfaces.AdminBotInterface;
import com.mulion.telegram_bot_application.role_interfaces.CaptainBotInterface;
import com.mulion.telegram_bot_application.role_interfaces.ManagerBotInterface;
import com.mulion.telegram_bot_application.services.MessageService;
import com.mulion.yclients.services.YCUserService;
import org.hibernate.Session;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class BotApplication extends TelegramLongPollingBot {
    public static final String TOKEN = "tg.token";
    public static final String BOT_USER_NAME = "tg.bot_user_name";
    private final SessionProvider provider;
    private final DBUserService userService;
    private final MessageService messageService;
    private final AdminBotInterface adminInterface;
    private final ManagerBotInterface managerInterface;
    private final CaptainBotInterface captainInterface;

    @Override
    public void onUpdateReceived(Update update) {
        Long userId = getUserId(update);
        if (userId == null) return;

        try (Session session = provider.getSessionFactory().openSession()) {
            provider.setSession(session);
            sessionStart(update, userId);
        }
    }

    private void sessionStart(Update update, Long userId) {
        User user = userService.getUser(userId);
        messageService.removeInlineButtons(user);

        if (!checkUser(update, user, userId)) return;

        UserRole access = user.getActionStep().getAction().getAccess();
        switch (access) {
            case ADMIN -> adminInterface.onUpdateReceived(user, update);
            case MANAGER -> managerInterface.onUpdateReceived(user, update);
            default -> captainInterface.onUpdateReceived(user, update);
        }
        userService.updateUser(user);
    }

    public BotApplication(String token) {
        super(token);
        provider = new SessionProvider();
        DBBoatService boatService = new DBBoatService(new BoatRepository(provider));
        ReportService reportService = new ReportService(boatService, new DBReportService(new ReportRepository(provider)));
        userService = new DBUserService(new UserRepository(provider), boatService);
        messageService = new MessageService(getOptions(), token);
        adminInterface = new AdminBotInterface(messageService, userService, boatService, reportService);
        managerInterface = adminInterface.getManagerInterface();
        captainInterface = adminInterface.getCaptainInterface();
    }

    public static void main(String[] args) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new BotApplication(ConfigService.getProperty(TOKEN)));
            System.out.println(BotMassageTexts.START_MESSAGE);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getBotUsername() {
        return ConfigService.getProperty(BOT_USER_NAME);
    }

    private Long getUserId(Update update) {
        long userId;
        if (!(update.hasMessage() && update.getMessage().hasText())) {
            if (!update.hasCallbackQuery()) {
                return null;
            }
            userId = update.getCallbackQuery().getFrom().getId();
        } else {
            userId = update.getMessage().getFrom().getId();
        }
        return userId;
    }

    private Long getChatId(Update update) {
        if (update.hasMessage()) {
            return update.getMessage().getChatId();
        }
        return update.getCallbackQuery().getMessage().getChatId();
    }

    private boolean checkUser(Update update, User user, long userId) {
        Long chatId = getChatId(update);
        if (user == null) {
            user = userService.addUser(
                    userId,
                    chatId,
                    update.getMessage().getFrom().getUserName(),
                    update.getMessage().getFrom().getFirstName()
            );
            messageService.sendText(update.getMessage().getChatId(), BotMassageTexts.REGISTRATION_GREETENG);
            registration(user, update);
            return false;
        }
        if (user.getActionStep().getAction() == Action.REGISTRATION) {
            registration(user, update);
            return false;
        }
        if (!chatId.equals(user.getChatId())) {
            user.setChatId(chatId);
        }
        return true;
    }

    private void registration(User user, Update update) {
        long chatId = update.getMessage().getChatId();
        String messageText = update.getMessage().getText();
        int step = userService.nextStep(user);
        switch (step) {
            case 0 -> messageService.sendText(chatId, BotMassageTexts.REGISTRATION_LOGIN);
            case 1 -> {
                messageService.sendText(chatId, BotMassageTexts.REGISTRATION_PASSWORD);
                user.setLogin(messageText);
                userService.updateUser(user);
            }
            case 2 -> {
                user.setPassword(messageText);
                if (!YCUserService.authorization(user)) {
                    messageService.sendText(chatId, BotMassageTexts.REGISTRATION_ERROR);
                    userService.restartAction(user);
                    registration(user, update);
                    return;
                }
                userService.inactive(user);
                messageService.sendText(chatId, BotMassageTexts.REGISTRATION_DONE);
                onUpdateReceived(update);
            }
            default -> {
                messageService.sendText(chatId, BotMassageTexts.REGISTRATION_ERROR);
                userService.restartAction(user);
                registration(user, update);
                return;
            }
        }
        System.out.println(user);
    }
}
