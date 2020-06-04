package theWorst.discord;

import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageAttachment;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.listener.message.MessageCreateListener;
import theWorst.DiscordBot;
import theWorst.Tools;

import java.awt.*;
import java.util.List;
import java.util.HashMap;



public class DiscordCommands implements MessageCreateListener {
    public HashMap<String,Command> commands = new HashMap<>();

    public void registerCommand(Command c){
        commands.put(c.name,c);
    }

    public boolean hasCommand(String command){
        return commands.containsKey(command);
    }

    @Override
    public void onMessageCreate(MessageCreateEvent messageCreateEvent) {
        String message = messageCreateEvent.getMessageContent();
        if(messageCreateEvent.getMessageAuthor().isBotUser()) return;
        if(!message.startsWith(DiscordBot.prefix)) return;
        if(DiscordBot.isInvalidChannel(messageCreateEvent)) return;
        int nameLength = message.indexOf(" ");
        if(nameLength<0){
            runCommand(message.replace(DiscordBot.prefix,""),new CommandContext(messageCreateEvent,new String[0],null));
            return;
        }
        String theMessage = message.substring(nameLength+1);
        String[] args = theMessage.split(" ");
        String name = message.substring(DiscordBot.prefix.length(),nameLength);
        runCommand(name,new CommandContext(messageCreateEvent,args,theMessage));
    }

    /**Validates command**/
    private void runCommand(String name, CommandContext ctx) {
        Command command=commands.get(name);

        if(command==null){
            String match = Tools.findBestMatch(ctx.args[0],commands.keySet());
            ctx.reply("Sorry i don t know this command.");
            if(match==null) return;
            ctx.reply("Did you mean "+match+"?");
            return;
        }
        if(!command.hasPerm(ctx)){
            EmbedBuilder msg= new EmbedBuilder()
                    .setColor(Color.red)
                    .setTitle("ACCESS DENIED!")
                    .setDescription("You don't have high enough permission to use this command.");
            ctx.channel.sendMessage(msg);
            return;
        }
        Message message=ctx.event.getMessage();
        List<MessageAttachment> mas = message.getAttachments();
        boolean tooFew = ctx.args.length<command.minArgs,tooMatch=ctx.args.length>command.maxArgs;
        boolean correctFiles = command.attachment==null || (mas.size() == 1 && mas.get(0).getFileName().endsWith(command.attachment));
        if(tooFew || tooMatch || !correctFiles){
            EmbedBuilder eb= new EmbedBuilder()
                    .setColor(Color.red)
                    .setDescription("Valid format : " + DiscordBot.prefix + name + " " + command.argStruct );
            if(tooFew) eb.setTitle("TOO FEW ARGUMENTS!" );
            else if(tooMatch) eb.setTitle( "TOO MATCH ARGUMENTS!");
            else eb.setTitle("INCORRECT ATTACHMENT!");
            ctx.channel.sendMessage(eb);
            return;
        }
        command.run(ctx);
    }
}
