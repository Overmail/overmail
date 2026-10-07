import outlookLogo from "$lib/assets/microsoft-outlook.svg";
import gmailLogo from "$lib/assets/gmail.svg";

/** How each provider the server may offer looks here. One it lists that is missing is skipped. */
export const OAUTH_PROVIDER_LOOKS: Record<string, {name: string; logo: string}> = {
    microsoft: {name: "Microsoft", logo: outlookLogo},
    google: {name: "Google", logo: gmailLogo},
};
